package com.github.highcumontoa.sensitivedatagatewayjava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.sensitivedatagatewayjava.audit.AuditService;
import com.github.highcumontoa.sensitivedatagatewayjava.classification.DataScanner;
import com.github.highcumontoa.sensitivedatagatewayjava.classification.DefaultClassificationRegistry;
import com.github.highcumontoa.sensitivedatagatewayjava.error.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.limit.GatewayLimits;
import com.github.highcumontoa.sensitivedatagatewayjava.limit.RequestGuard;
import com.github.highcumontoa.sensitivedatagatewayjava.model.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.model.AccessResponse;
import com.github.highcumontoa.sensitivedatagatewayjava.model.AuditRecord;
import com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode;
import com.github.highcumontoa.sensitivedatagatewayjava.model.DenyResponse;
import com.github.highcumontoa.sensitivedatagatewayjava.model.EvaluationResult;
import com.github.highcumontoa.sensitivedatagatewayjava.model.FieldTransformMarker;
import com.github.highcumontoa.sensitivedatagatewayjava.model.FieldDecision;
import com.github.highcumontoa.sensitivedatagatewayjava.model.LocatedField;
import com.github.highcumontoa.sensitivedatagatewayjava.model.PolicySnapshot;
import com.github.highcumontoa.sensitivedatagatewayjava.model.PolicyVersion;
import com.github.highcumontoa.sensitivedatagatewayjava.model.PolicyVersionResolution;
import com.github.highcumontoa.sensitivedatagatewayjava.model.TransformType;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.PolicyEvaluator;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.PolicyStore;
import com.github.highcumontoa.sensitivedatagatewayjava.transform.DataTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 默认编排实现。关键顺序保证：
 * <ol>
 *   <li>请求校验与规模/耗时限制；</li>
 *   <li>读取单一一致策略快照并解析版本（显式回退旧版本立即拒绝）；</li>
 *   <li>扫描数据（缺失/未分级/格式异常均拒绝）；</li>
 *   <li>判定（未授权/用途不符/策略缺失/等级超限均可区分）；</li>
 *   <li>先写审计；审计失败直接抛出，绝不返回数据；</li>
 *   <li>仅放行时在深拷贝上转换；任何失败都不触碰原始数据、不产生残留结果。</li>
 * </ol>
 */
@Service
public class DefaultGatewayService implements GatewayService {

    private static final Logger log = LoggerFactory.getLogger(DefaultGatewayService.class);

    private final PolicyStore policyStore;
    private final PolicyEvaluator evaluator;
    private final DataScanner scanner;
    private final DataTransformer transformer;
    private final AuditService auditService;
    private final RequestGuard guard;
    private final GatewayLimits limits;
    private final ObjectMapper objectMapper;

    public DefaultGatewayService(PolicyStore policyStore,
                                 PolicyEvaluator evaluator,
                                 DataScanner scanner,
                                 DataTransformer transformer,
                                 AuditService auditService,
                                 RequestGuard guard,
                                 GatewayLimits limits,
                                 ObjectMapper objectMapper) {
        this.policyStore = policyStore;
        this.evaluator = evaluator;
        this.scanner = scanner;
        this.transformer = transformer;
        this.auditService = auditService;
        this.guard = guard;
        this.limits = limits;
        this.objectMapper = objectMapper;
    }

    @Override
    public AccessResponse access(AccessRequest request, String rawInputJson) {
        validateShape(request);
        guard.checkPayloadSize(rawInputJson, limits.getMaxPayloadBytes());
        RequestGuard.Deadline deadline = guard.start(limits.getProcessTimeoutMillis());

        PolicySnapshot snapshot = policyStore.snapshot();
        deadline.checkExpired();

        // 版本解析：未指定→最新；指定不存在→POLICY_MISSING；显式回退旧版本→拒绝
        PolicyVersionResolution resolution =
                resolveVersion(request.policyVersion(), snapshot);
        if (resolution.fallbackDenied()) {
            EvaluationResult result = new EvaluationResult(
                    DecisionCode.POLICY_FALLBACK_DENIED,
                    "显式请求旧策略版本 " + resolution.requestedVersion()
                            + " 被拒绝：当前最新版本为 " + resolution.latestVersion()
                            + "，历史版本仅用于解释既有审计，不得用于新请求",
                    resolution, List.of());
            AuditRecord rec = auditService.write(request, result, rawInputJson);
            log.warn("拒绝原因=POLICY_FALLBACK_DENIED auditId={}", rec.auditId());
            throw new DeniedException(rec.auditId(), resolution.requestedVersion(),
                    DecisionCode.POLICY_FALLBACK_DENIED, result.reason(), true);
        }
        PolicyVersion version = snapshot.versions().get(resolution.resolvedVersion());

        // 扫描（深拷贝后扫描，保证后续任何处理都不修改入参）
        @SuppressWarnings("unchecked")
        Map<String, Object> workingCopy =
                objectMapper.convertValue(request.payload(), Map.class);
        List<LocatedField> located;
        try {
            located = scanner.scan(workingCopy,
                    DefaultClassificationRegistry.bind(version.classifications()),
                    limits.getMaxDepth(), limits.getMaxFieldCount());
        } catch (GatewayException scanFailure) {
            // 数据质量类拒绝（缺失/未分级/类型异常/深度超限）同样审计留痕
            EvaluationResult rejected = new EvaluationResult(
                    scanFailure.getCode(), scanFailure.getMessage(), resolution, List.of());
            AuditRecord rec = auditService.write(request, rejected, rawInputJson);
            throw new DeniedException(rec.auditId(), resolution.resolvedVersion(),
                    scanFailure.getCode(), scanFailure.getMessage(), true);
        }
        deadline.checkExpired();

        EvaluationResult result = evaluator.evaluate(
                request, located, version, snapshot.latestVersion());
        deadline.checkExpired();

        AuditRecord rec = auditService.write(request, result, rawInputJson);
        if (!result.allowed()) {
            throw new DeniedException(rec.auditId(),
                    result.versionResolution().resolvedVersion(),
                    result.overallCode(), result.reason(), true);
        }

        // 仅在审计成功且全部放行后转换；异常时 workingCopy 为局部对象，随 GC 回收，无残留
        List<FieldDecision> decisions = new ArrayList<>(result.fieldDecisions());
        transformer.apply(workingCopy, located, decisions,
                result.versionResolution().resolvedVersion());
        deadline.checkExpired();

        List<FieldTransformMarker> markers = decisions.stream()
                .map(d -> new FieldTransformMarker(
                        d.fieldPath(), d.level(), d.transform(),
                        d.transform() == TransformType.REDACT
                                || d.transform() == TransformType.TOKENIZE))
                .toList();
        return new AccessResponse(rec.auditId(),
                result.versionResolution().resolvedVersion(), workingCopy, markers);
    }

    @Override
    public DenyResponse deny(String auditId, String policyVersion, DecisionCode code,
                             String reason, boolean audited) {
        return new DenyResponse(auditId, policyVersion, code, reason, audited);
    }

    private void validateShape(AccessRequest request) {
        if (request == null) {
            throw new GatewayException(DecisionCode.BAD_REQUEST, "请求体为空");
        }
        if (request.callerId() == null || request.callerId().isBlank()) {
            throw new GatewayException(DecisionCode.BAD_REQUEST, "缺少 callerId");
        }
        if (request.purpose() == null || request.purpose().isBlank()) {
            throw new GatewayException(DecisionCode.BAD_REQUEST, "缺少 purpose");
        }
        if (request.payload() == null) {
            throw new GatewayException(DecisionCode.FIELD_MISSING, "缺少 payload");
        }
    }

    private PolicyVersionResolution resolveVersion(String requested, PolicySnapshot snapshot) {
        String latest = snapshot.latestVersion();
        if (requested == null || requested.isBlank()) {
            return new PolicyVersionResolution(null, latest, latest, false);
        }
        if (!snapshot.versions().containsKey(requested)) {
            throw new GatewayException(DecisionCode.POLICY_MISSING,
                    "请求的策略版本不存在: " + requested);
        }
        boolean fallback = !requested.equals(latest);
        return new PolicyVersionResolution(requested, requested, latest, fallback);
    }

    /** 携带已审计拒绝信息的内部异常，由控制器转为结构化 DenyResponse。 */
    public static final class DeniedException extends GatewayException {
        private final String auditId;
        private final String policyVersion;
        private final boolean audited;

        public DeniedException(String auditId, String policyVersion,
                               DecisionCode code, String reason, boolean audited) {
            super(code, reason);
            this.auditId = auditId;
            this.policyVersion = policyVersion;
            this.audited = audited;
        }

        public String getAuditId() {
            return auditId;
        }

        public String getPolicyVersion() {
            return policyVersion;
        }

        public boolean isAudited() {
            return audited;
        }
    }
}
