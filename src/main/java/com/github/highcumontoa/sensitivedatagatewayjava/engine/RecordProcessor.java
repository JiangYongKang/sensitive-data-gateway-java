package com.github.highcumontoa.sensitivedatagatewayjava.engine;

import com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationDefinition;
import com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationService;
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
 * 单条业务记录处理器：遍历 Map/List/标量树，做识别、判定与转换。
 *
 * <p>单条访问与批量访问共用同一套逐字段逻辑，保证两条入口的判定与转换完全一致：
 * <ol>
 *   <li>遍历前强制嵌套深度与节点规模上限（上限值与错误码由调用方按场景传入）；</li>
 *   <li>逐字段识别分级：命中未分级清单 -> CLASSIFICATION_UNDEFINED；</li>
 *   <li>敏感字段逐字段策略判定：未授权/用途不符/等级不足即时拒绝；</li>
 *   <li>允许字段做 NONE/MASK/REDACT/TOKENIZE，层级与 JSON 类型族保持不变；</li>
 *   <li>处理中检查耗时上限；任何拒绝抛出携带记录内字段路径的异常，不产生半成品。</li>
 * </ol>
 * 本组件不感知“批内序号”：抛出的异常只带字段路径，序号由批量引擎在外层附加。
 */
@Component
public class RecordProcessor {

    private static final Logger log = LoggerFactory.getLogger(RecordProcessor.class);

    private final ClassificationService classificationService;
    private final PolicyService policyService;
    private final TransformerFactory transformerFactory;

    public RecordProcessor(ClassificationService classificationService,
                           PolicyService policyService,
                           TransformerFactory transformerFactory) {
        this.classificationService = classificationService;
        this.policyService = policyService;
        this.transformerFactory = transformerFactory;
    }

    /** 处理单条记录的产出：转换后的树、字段说明、判定依据文本。 */
    public record Outcome(Object data, List<FieldResult> fieldResults, String decisionBasis) {
    }

    /**
     * @param maxDepth          嵌套深度上限
     * @param maxNodes          节点规模上限
     * @param startNanos        整个请求（单条或整批）的起始时刻，用于统一耗时预算
     * @param timeoutMs         耗时上限
     * @param sizeExceededCode  规模超限错误码（单条 SIZE_LIMIT_EXCEEDED / 批内 RECORD_SIZE_LIMIT_EXCEEDED）
     * @param timeoutCode       耗时超限错误码（单条 TIMEOUT_EXCEEDED / 整批 BATCH_TIMEOUT_EXCEEDED）
     */
    public Outcome process(Object root, AccessRequest request, AccessPolicy policy,
                           ClassificationDefinition classification,
                           int maxDepth, long maxNodes, long startNanos, long timeoutMs,
                           GatewayErrorCode sizeExceededCode, GatewayErrorCode timeoutCode) {
        if (!(root instanceof Map)) {
            throw new GatewayException(GatewayErrorCode.DATA_MALFORMED,
                    "record must be a JSON object", null, null);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> rootMap = (Map<String, Object>) root;

        Counter counter = new Counter();
        Limits.check(rootMap, 1, maxDepth, maxNodes, counter, sizeExceededCode);

        checkRequiredFields(rootMap, classification, maxDepth);

        List<FieldResult> fieldResults = new ArrayList<>();
        List<String> basis = new ArrayList<>();
        Object processed = transformNode(rootMap, "", 1, request, policy, classification,
                fieldResults, basis, startNanos, timeoutMs, timeoutCode, maxDepth);

        return new Outcome(processed, List.copyOf(fieldResults), String.join(" | ", basis));
    }

    private void checkRequiredFields(Map<String, Object> root,
                                     ClassificationDefinition classification, int maxDepth) {
        for (String required : classification.required()) {
            if (!isPresent(root, required, maxDepth)) {
                throw new GatewayException(GatewayErrorCode.FIELD_MISSING,
                        "required sensitive field is missing: " + required, null, required);
            }
        }
    }

    private boolean isPresent(Map<String, Object> root, String key, int maxDepth) {
        if (root.containsKey(key)) {
            return root.get(key) != null;
        }
        return findLeaf(root, leafName(key), 0, maxDepth);
    }

    private boolean findLeaf(Object node, String leaf, int depth, int maxDepth) {
        if (depth > maxDepth) {
            return false;
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (leaf.equals(String.valueOf(e.getKey())) && e.getValue() != null) {
                    return true;
                }
                if (findLeaf(e.getValue(), leaf, depth + 1, maxDepth)) {
                    return true;
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                if (findLeaf(item, leaf, depth + 1, maxDepth)) {
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
                                 long startNanos, long timeoutMs, GatewayErrorCode timeoutCode,
                                 int maxDepth) {
        checkTimeout(startNanos, timeoutMs, timeoutCode, path);
        SensitivityLevel level = path.isEmpty() ? null
                : classificationService.classify(classification, path);
        if (level != null) {
            return transformClassified(node, path, level, request, policy, fieldResults, basis);
        }
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String childPath = path.isEmpty() ? key : path + "." + key;
                out.put(key, transformNode(entry.getValue(), childPath, depth + 1,
                        request, policy, classification, fieldResults, basis,
                        startNanos, timeoutMs, timeoutCode, maxDepth));
            }
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                // 数组归属于其父字段：元素路径为该字段路径后追加 []；
                // leafName 取最后一个 '.' 之后，多级如 email[][].email 的叶子仍是 email。
                out.add(transformNode(item, path + "[]", depth + 1,
                        request, policy, classification, fieldResults, basis,
                        startNanos, timeoutMs, timeoutCode, maxDepth));
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
                    "access denied for field " + path + " [" + decision.basis() + "]",
                    null, path);
        }
        TransformType transformType = decision.transform() == null ? TransformType.REDACT : decision.transform();
        if (node == null) {
            throw new GatewayException(GatewayErrorCode.DATA_MALFORMED,
                    "classified field has null value: " + path, null, path);
        }
        if (node instanceof Map) {
            // 命中分级的键为对象：数据契约异常，不递归穿透，避免敏感容器被当普通子树静默透传
            throw new GatewayException(GatewayErrorCode.DATA_MALFORMED,
                    "classified field must be a scalar or array value: " + path
                            + " (actual=object)", null, path);
        }
        if (node instanceof List<?> list) {
            // 允许“敏感字段 -> 标量数组”：逐元素施加同一转换，数组元素个数与层级保持；
            // 元素本身仍必须是可转换标量（null/对象/数组元素 -> DATA_MALFORMED）。
            List<Object> transformedList = new ArrayList<>(list.size());
            for (Object element : list) {
                transformedList.add(transformScalar(element, path, transformType, policy.version()));
            }
            fieldResults.add(new FieldResult(path, level, transformType,
                    transformType == TransformType.NONE,
                    transformType != TransformType.NONE));
            return transformedList;
        }
        Object transformed = transformScalar(node, path, transformType, policy.version());
        fieldResults.add(new FieldResult(path, level, transformType,
                reversibleOf(transformType), transformType != TransformType.NONE));
        return transformed;
    }

    private boolean reversibleOf(TransformType transformType) {
        return transformType == TransformType.NONE;
    }

    private Object transformScalar(Object node, String path, TransformType transformType,
                                   String policyVersion) {
        if (node == null) {
            throw new GatewayException(GatewayErrorCode.DATA_MALFORMED,
                    "classified array element has null value: " + path, null, path);
        }
        if (node instanceof Map || node instanceof List) {
            throw new GatewayException(GatewayErrorCode.DATA_MALFORMED,
                    "classified array element must be a scalar: " + path
                            + " (actual=" + (node instanceof Map ? "object" : "array") + ")",
                    null, path);
        }
        if (!(node instanceof String) && transformType != TransformType.NONE) {
            throw new GatewayException(GatewayErrorCode.DATA_MALFORMED,
                    "classified field must be a string for transform: " + path
                            + " (actual=" + node.getClass().getSimpleName() + ")",
                    null, path);
        }
        ValueTransformer transformer = transformerFactory.create(transformType, policyVersion, path);
        return transformer.apply(node);
    }

    private void checkTimeout(long startNanos, long timeoutMs,
                              GatewayErrorCode timeoutCode, String path) {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (elapsedMs > timeoutMs) {
            throw new GatewayException(timeoutCode,
                    "processing timeout while handling field: " + path, null, path);
        }
    }

    private static String leafName(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    /**
     * 深度与规模静态检查（在任何转换之前执行，避免半成品）。
     */
    static final class Limits {

        static void check(Object node, int depth, int maxDepth, long maxNodes,
                          Counter counter, GatewayErrorCode sizeExceededCode) {
            counter.nodes++;
            if (depth > maxDepth) {
                throw new GatewayException(GatewayErrorCode.DEPTH_LIMIT_EXCEEDED,
                        "nesting depth " + depth + " exceeds limit " + maxDepth);
            }
            if (counter.nodes > maxNodes) {
                throw new GatewayException(sizeExceededCode,
                        "record node count exceeds limit " + maxNodes);
            }
            if (node instanceof Map<?, ?> map) {
                for (Object value : map.values()) {
                    check(value, depth + 1, maxDepth, maxNodes, counter, sizeExceededCode);
                }
            } else if (node instanceof List<?> list) {
                for (Object item : list) {
                    check(item, depth + 1, maxDepth, maxNodes, counter, sizeExceededCode);
                }
            }
        }
    }

    static final class Counter {
        long nodes;
    }
}
