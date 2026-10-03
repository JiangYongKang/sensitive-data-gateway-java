package com.github.highcumontoa.sensitivedatagatewayjava.engine;

import com.github.highcumontoa.sensitivedatagatewayjava.classification.Classification;
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
 *   <li>允许字段做 NONE/MASK/REDACT/TOKENIZE，层级与 JSON 类型族保持不变；
 *       命分级的键若承载标量数组，则逐元素转换、保持数组长度与顺序，
 *       且同一转换器派生自命中的分级字段键，跨记录/跨层级/跨数组位置同值同令牌；</li>
 *   <li>处理中检查耗时上限；任意拒绝直接抛出异常，不返回半成品数据。</li>
 * </ol>
 * 路径采用双轨表示：规范路径（如 {@code contacts[].email}，{@code []} 不区分元素位置）
 * 用于分级匹配与审计字段路径；定位路径（如 {@code contacts[2].email}）
 * 仅用于把失败定位到具体数组元素/记录字段。令牌密钥派生使用命中的分级字段键
 * （见 {@link Classification#fieldKey()}），与规范路径解耦。
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
        Traversal.checkLimits(root, 1, properties.getMaxDepth(),
                properties.getMaxPayloadNodes(), counter,
                null, "", GatewayErrorCode.DEPTH_LIMIT_EXCEEDED,
                GatewayErrorCode.SIZE_LIMIT_EXCEEDED, startNanos,
                properties.getProcessingTimeoutMs());

        checkRequiredFields(root, classification, null, startNanos);

        Ctx ctx = new Ctx(request, policy, classification, null, startNanos);
        Object processed = transformNode(root, "", "", 1, ctx);

        String basisText = String.join(" | ", ctx.basis);
        return new Result(processed, List.copyOf(ctx.fieldResults), basisText, null, true);
    }

    @Override
    public void preflightBatch(List<Object> records, AccessRequest request,
                               AccessPolicy policy, ClassificationDefinition classification,
                               int maxRecordDepth, long maxTotalNodes, long startNanos) {
        Counter total = new Counter();
        for (int i = 0; i < records.size(); i++) {
            Object record = records.get(i);
            if (!(record instanceof Map)) {
                throw batchFail(GatewayErrorCode.DATA_MALFORMED, i, null,
                        "batch record must be a JSON object", startNanos);
            }
            Traversal.checkLimits(record, 1, maxRecordDepth, maxTotalNodes, total,
                    i, "", GatewayErrorCode.BATCH_RECORD_DEPTH_EXCEEDED,
                    GatewayErrorCode.BATCH_SIZE_LIMIT_EXCEEDED, startNanos,
                    properties.getProcessingTimeoutMs());
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) record;
            checkRequiredFields(root, classification, i, startNanos);
        }
    }

    @Override
    public RecordResult processBatchRecord(List<Object> records, int recordIndex, AccessRequest request,
                                           AccessPolicy policy, ClassificationDefinition classification,
                                           long startNanos) {
        Object record = records.get(recordIndex);
        if (!(record instanceof Map)) {
            throw batchFail(GatewayErrorCode.DATA_MALFORMED, recordIndex, null,
                    "batch record must be a JSON object", startNanos);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) record;
        Ctx ctx = new Ctx(request, policy, classification, recordIndex, startNanos);
        Object processed = transformNode(root, "", "", 1, ctx);
        return new RecordResult(processed, List.copyOf(ctx.fieldResults),
                String.join(" | ", ctx.basis));
    }

    private void checkRequiredFields(Map<String, Object> root, ClassificationDefinition classification,
                                     Integer recordIndex, long startNanos) {
        for (String required : classification.required()) {
            if (!isPresent(root, required)) {
                throw batchFail(GatewayErrorCode.FIELD_MISSING, recordIndex, null,
                        "required sensitive field is missing: " + required, startNanos);
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

    private Object transformNode(Object node, String canonical, String locator, int depth, Ctx ctx) {
        checkTimeout(ctx.startNanos, ctx.recordIndex, locator);
        // 每个节点先做字段识别：命中分级的键必须是标量或标量数组，绝不递归穿透成普通子树
        Classification matched = canonical.isEmpty() ? null
                : classificationService.match(ctx.classification, canonical);
        if (matched != null) {
            return transformClassified(node, canonical, locator, matched, ctx);
        }
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String childCanonical = canonical.isEmpty() ? key : canonical + "." + key;
                String childLocator = locator.isEmpty() ? key : locator + "." + key;
                out.put(key, transformNode(entry.getValue(), childCanonical, childLocator,
                        depth + 1, ctx));
            }
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (int idx = 0; idx < list.size(); idx++) {
                // 规范路径对数组元素一视同仁（[]），保证跨位置/跨记录同值同策略同令牌
                String childCanonical = canonical + "[]";
                String childLocator = locator + "[" + idx + "]";
                out.add(transformNode(list.get(idx), childCanonical, childLocator,
                        depth + 1, ctx));
            }
            return out;
        }
        return node;
    }

    private Object transformClassified(Object node, String canonical, String locator,
                                       Classification matched, Ctx ctx) {
        SensitivityLevel level = matched.level();
        PolicyService.Decision decision = policyService.evaluate(ctx.policy, ctx.request, level);
        ctx.basis.add(decision.basis());
        log.info("FIELD decision inputField={} rawType={} caller={} purpose={} recordIndex={} basis={}",
                canonical, node == null ? "null" : node.getClass().getSimpleName(),
                ctx.request.callerId(), ctx.request.purpose(), ctx.recordIndex, decision.basis());
        if (!decision.allowed()) {
            throw batchFail(decision.denyReason(), ctx.recordIndex, locator,
                    "access denied for field " + canonical + " [" + decision.basis() + "]",
                    ctx.startNanos);
        }
        TransformType transformType = decision.transform() == null ? TransformType.REDACT : decision.transform();
        if (node == null) {
            throw batchFail(GatewayErrorCode.DATA_MALFORMED, ctx.recordIndex, locator,
                    "classified field has null value: " + canonical, ctx.startNanos);
        }
        if (node instanceof Map) {
            // 命中分级的键必须是标量（或标量数组）；对象值说明上游数据契约异常，
            // 不递归穿透，避免把敏感容器当普通子树静默透传
            throw batchFail(GatewayErrorCode.DATA_MALFORMED, ctx.recordIndex, locator,
                    "classified field must be a scalar or scalar array: " + canonical
                            + " (actual=object)", ctx.startNanos);
        }
        // 令牌密钥按“命中的分级字段键”派生，而非记录内出现位置：
        // 同一原始值在同一分级字段、同一策略版本下，无论出现在顶层、嵌套对象
        // 还是任意数组下标、哪条记录，令牌都一致；不同分级字段/不同版本仍可区分。
        ValueTransformer transformer = transformerFactory.create(
                transformType, ctx.policy.version(), matched.fieldKey());
        Object transformed;
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (int idx = 0; idx < list.size(); idx++) {
                Object element = list.get(idx);
                String elementLocator = locator + "[" + idx + "]";
                if (element == null || element instanceof Map || element instanceof List) {
                    throw batchFail(GatewayErrorCode.DATA_MALFORMED, ctx.recordIndex, elementLocator,
                            "classified array elements must be scalars: " + canonical
                                    + "[" + idx + "]", ctx.startNanos);
                }
                if (!(element instanceof String) && transformType != TransformType.NONE) {
                    throw batchFail(GatewayErrorCode.DATA_MALFORMED, ctx.recordIndex, elementLocator,
                            "classified array elements must be strings for transform: "
                                    + canonical + "[" + idx + "]"
                                    + " (actual=" + element.getClass().getSimpleName() + ")",
                            ctx.startNanos);
                }
                out.add(transformer.apply(element));
            }
            transformed = out;
        } else {
            if (!(node instanceof String) && transformType != TransformType.NONE) {
                throw batchFail(GatewayErrorCode.DATA_MALFORMED, ctx.recordIndex, locator,
                        "classified field must be a string for transform: " + canonical
                                + " (actual=" + node.getClass().getSimpleName() + ")",
                        ctx.startNanos);
            }
            transformed = transformer.apply(node);
        }
        ctx.fieldResults.add(new FieldResult(canonical, level, transformType,
                transformer.reversible(), transformType != TransformType.NONE));
        return transformed;
    }

    private void checkTimeout(long startNanos, Integer recordIndex, String locator) {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (elapsedMs > properties.getProcessingTimeoutMs()) {
            throw batchFail(GatewayErrorCode.TIMEOUT_EXCEEDED, recordIndex, locator,
                    "processing timeout while handling field: " + locator, startNanos);
        }
    }

    private GatewayException batchFail(GatewayErrorCode code, Integer recordIndex,
                                       String fieldPath, String message, long startNanos) {
        checkTimeoutSilently(recordIndex, startNanos, code);
        String located = fieldPath == null ? message : message + " (field=" + fieldPath + ")";
        String withRecord = recordIndex == null ? located
                : "batch record[" + recordIndex + "]: " + located;
        return new GatewayException(code, withRecord, recordIndex, fieldPath);
    }

    private void checkTimeoutSilently(Integer recordIndex, long startNanos, GatewayErrorCode original) {
        // 超时优先于其他原因上报，避免长批次以业务错误掩盖容量问题
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (elapsedMs > properties.getProcessingTimeoutMs()
                && original != GatewayErrorCode.TIMEOUT_EXCEEDED) {
            throw new GatewayException(GatewayErrorCode.TIMEOUT_EXCEEDED,
                    "batch processing timeout exceeded before completion", recordIndex, null);
        }
    }

    private static String leafName(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    /**
     * 遍历上下文：整批共享版本快照与起始时间；recordIndex 为 null 表示单条请求。
     */
    private final class Ctx {
        final AccessRequest request;
        final AccessPolicy policy;
        final ClassificationDefinition classification;
        final Integer recordIndex;
        final long startNanos;
        final List<FieldResult> fieldResults = new ArrayList<>();
        final List<String> basis = new ArrayList<>();

        Ctx(AccessRequest request, AccessPolicy policy, ClassificationDefinition classification,
            Integer recordIndex, long startNanos) {
            this.request = request;
            this.policy = policy;
            this.classification = classification;
            this.recordIndex = recordIndex;
            this.startNanos = startNanos;
        }
    }

    /**
     * 深度与规模静态检查（在任何转换之前执行，避免半成品）。
     */
    private static final class Traversal {

        private static void checkLimits(Object node, int depth, int maxDepth, long maxNodes,
                                        Counter counter, Integer recordIndex, String locator,
                                        GatewayErrorCode depthCode, GatewayErrorCode sizeCode,
                                        long startNanos, long timeoutMs) {
            counter.nodes++;
            if (depth > maxDepth) {
                throw new GatewayException(depthCode,
                        (recordIndex == null ? "" : "batch record[" + recordIndex + "]: ")
                                + "nesting depth " + depth + " exceeds limit " + maxDepth
                                + (locator.isEmpty() ? "" : " (field=" + locator + ")"),
                        recordIndex, locator.isEmpty() ? null : locator);
            }
            if (counter.nodes > maxNodes) {
                throw new GatewayException(sizeCode,
                        (recordIndex == null ? "" : "batch record[" + recordIndex + "]: ")
                                + "payload node count exceeds limit " + maxNodes,
                        recordIndex, locator.isEmpty() ? null : locator);
            }
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            if (elapsedMs > timeoutMs) {
                throw new GatewayException(GatewayErrorCode.TIMEOUT_EXCEEDED,
                        "processing timeout during batch preflight (record=" + recordIndex + ")",
                        recordIndex, null);
            }
            if (node instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    String key = String.valueOf(e.getKey());
                    String child = locator.isEmpty() ? key : locator + "." + key;
                    checkLimits(e.getValue(), depth + 1, maxDepth, maxNodes, counter,
                            recordIndex, child, depthCode, sizeCode, startNanos, timeoutMs);
                }
            } else if (node instanceof List<?> list) {
                for (int idx = 0; idx < list.size(); idx++) {
                    String child = locator + "[" + idx + "]";
                    checkLimits(list.get(idx), depth + 1, maxDepth, maxNodes, counter,
                            recordIndex, child, depthCode, sizeCode, startNanos, timeoutMs);
                }
            }
        }
    }

    private static final class Counter {
        private long nodes;
    }
}
