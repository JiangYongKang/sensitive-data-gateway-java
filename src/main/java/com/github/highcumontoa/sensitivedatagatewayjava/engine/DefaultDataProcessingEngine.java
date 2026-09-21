package com.github.highcumontoa.sensitivedatagatewayjava.engine;

import com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationDefinition;
import com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationService;
import com.github.highcumontoa.sensitivedatagatewayjava.config.GatewayProperties;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.FieldResult;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayErrorCode;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.SensitivityLevel;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.TransformType;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.AccessPolicy;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.PolicyService;
import com.github.highcumontoa.sensitivedatagatewayjava.transform.TransformerFactory;
import com.github.highcumontoa.sensitivedatagatewayjava.transform.ValueTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认数据处理引擎。
 * <p>遍历 Jackson 解析出的 Map/List/标量树：
 * <ol>
 *   <li>遍历前强制嵌套深度与节点规模上限；</li>
 *   <li>逐字段识别分级：分级未定义且命中未分级清单 -> CLASSIFICATION_UNDEFINED；</li>
 *   <li>敏感字段逐字段做策略判定：未授权/用途不匹配/等级不足即时拒绝；</li>
 *   <li>允许字段做 NONE/MASK/REDACT/TOKENIZE，层级与 JSON 类型族保持不变；</li>
 *   <li>处理中检查耗时上限；任意拒绝直接抛出异常，不返回半成品数据。</li>
 * </ol>
 * 判定基于传入的版本快照，与缓存刷新相互独立。
 */
@Component
public class DefaultDataProcessingEngine implements DataProcessingEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultDataProcessingEngine.class);

    private final ClassificationService classificationService;
    private final PolicyService policyService;
    private final TransformerFactory transformerFactory;
    private final GatewayProperties properties;

    public DefaultDataProcessingEngine(ClassificationService classificationService,
                                       PolicyService policyService,
                                       TransformerFactory transformerFactory,
                                       GatewayProperties properties) {
        this.classificationService = classificationService;
        this.policyService = policyService;
        this.transformerFactory = transformerFactory;
        this.properties = properties;
    }

    @Override
    public Result process(AccessRequest request, AccessPolicy policy, ClassificationDefinition classification) {
        long startNanos = System.nanoTime();
        Object payload = request.payload();
        if (!(payload instanceof Map)) {
            throw new GatewayException(GatewayErrorCode.DATA_MALFORMED,
                    "payload must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) payload;

        Counter counter = new Counter();
        Traversal.checkLimits(root, 1, properties.getMaxDepth(), properties.getMaxPayloadNodes(), counter);

        // 先校验必备敏感字段是否缺失（在任何转换之前）
        checkRequiredFields(root, classification);

        List<FieldResult> fieldResults = new ArrayList<>();
        List<String> basis = new ArrayList<>();
        Object processed = transformNode(root, "", 1, request, policy, classification,
                fieldResults, basis, startNanos);

        String basisText = String.join(" | ", basis);
        return new Result(processed, List.copyOf(fieldResults), basisText, null, true);
    }

    private void checkRequiredFields(Map<String, Object> root, ClassificationDefinition classification) {
        for (String required : classification.required()) {
            if (!isPresent(root, required)) {
                throw new GatewayException(GatewayErrorCode.FIELD_MISSING,
                        "required sensitive field is missing: " + required);
            }
        }
    }

    private boolean isPresent(Map<String, Object> root, String key) {
        if (root.containsKey(key)) {
            return root.get(key) != null;
        }
        // 支持裸字段名：任意层级出现即视为存在
        return findLeaf(root, leafName(key), 0);
    }

    private boolean findLeaf(Object node, String leaf, int depth) {
        if (depth > properties.getMaxDepth()) {
            return false;
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (leaf.equals(String.valueOf(e.getKey())) && e.getValue() != null) {
                    return true;
                }
                if (findLeaf(e.getValue(), leaf, depth + 1)) {
                    return true;
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                if (findLeaf(item, leaf, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Object transformNode(Object node, String path, int depth,
                                 AccessRequest request, AccessPolicy policy,
                                 ClassificationDefinition classification,
                                 List<FieldResult> fieldResults, List<String> basis,
                                 long startNanos) {
        checkTimeout(startNanos, path);
        // 每个节点先做字段识别：命中分级的键必须是标量，绝不递归穿透成普通子树
        SensitivityLevel level = path.isEmpty() ? null
                : classificationService.classify(classification, path);
        if (level != null) {
            return transformClassified(node, path, level, request, policy,
                    fieldResults, basis);
        }
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String childPath = path.isEmpty() ? key : path + "." + key;
                out.put(key, transformNode(entry.getValue(), childPath, depth + 1,
                        request, policy, classification, fieldResults, basis, startNanos));
            }
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(transformNode(item, path + "[]", depth + 1,
                        request, policy, classification, fieldResults, basis, startNanos));
            }
            return out;
        }
        return node;
    }

    private Object transformClassified(Object node, String path, SensitivityLevel level,
                                       AccessRequest request, AccessPolicy policy,
                                       List<FieldResult> fieldResults, List<String> basis) {
        PolicyService.Decision decision = policyService.evaluate(policy, request, level);
        basis.add(decision.basis());
        log.info("FIELD decision inputField={} rawType={} caller={} purpose={} basis={}",
                path, node == null ? "null" : node.getClass().getSimpleName(),
                request.callerId(), request.purpose(), decision.basis());
        if (!decision.allowed()) {
            throw new GatewayException(decision.denyReason(),
                    "access denied for field " + path + " [" + decision.basis() + "]");
        }
        TransformType transformType = decision.transform() == null ? TransformType.REDACT : decision.transform();
        if (node == null) {
            throw new GatewayException(GatewayErrorCode.DATA_MALFORMED,
                    "classified field has null value: " + path);
        }
        if (node instanceof Map || node instanceof List) {
            // 命中分级的键必须是标量；结构化值说明上游数据契约异常，
            // 不递归穿透，避免把敏感容器当普通子树静默透传
            throw new GatewayException(GatewayErrorCode.DATA_MALFORMED,
                    "classified field must be a scalar value: " + path
                            + " (actual=" + (node instanceof Map ? "object" : "array") + ")");
        }
        if (!(node instanceof String) && transformType != TransformType.NONE) {
            throw new GatewayException(GatewayErrorCode.DATA_MALFORMED,
                    "classified field must be a string for transform: " + path
                            + " (actual=" + node.getClass().getSimpleName() + ")");
        }
        ValueTransformer transformer = transformerFactory.create(transformType, policy.version(), path);
        Object transformed = transformer.apply(node);
        fieldResults.add(new FieldResult(path, level, transformType,
                transformer.reversible(), transformType != TransformType.NONE));
        return transformed;
    }

    private void checkTimeout(long startNanos, String path) {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (elapsedMs > properties.getProcessingTimeoutMs()) {
            throw new GatewayException(GatewayErrorCode.TIMEOUT_EXCEEDED,
                    "processing timeout while handling field: " + path);
        }
    }

    private static String leafName(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    /**
     * 深度与规模静态检查（在任何转换之前执行，避免半成品）。
     */
    private static final class Traversal {

        private static void checkLimits(Object node, int depth, int maxDepth, long maxNodes, Counter counter) {
            counter.nodes++;
            if (depth > maxDepth) {
                throw new GatewayException(GatewayErrorCode.DEPTH_LIMIT_EXCEEDED,
                        "nesting depth " + depth + " exceeds limit " + maxDepth);
            }
            if (counter.nodes > maxNodes) {
                throw new GatewayException(GatewayErrorCode.SIZE_LIMIT_EXCEEDED,
                        "payload node count exceeds limit " + maxNodes);
            }
            if (node instanceof Map<?, ?> map) {
                for (Object value : map.values()) {
                    checkLimits(value, depth + 1, maxDepth, maxNodes, counter);
                }
            } else if (node instanceof List<?> list) {
                for (Object item : list) {
                    checkLimits(item, depth + 1, maxDepth, maxNodes, counter);
                }
            }
        }
    }

    private static final class Counter {
        private long nodes;
    }
}
