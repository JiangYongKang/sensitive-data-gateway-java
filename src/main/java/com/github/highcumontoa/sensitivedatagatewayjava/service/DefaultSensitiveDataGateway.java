package com.github.highcumontoa.sensitivedatagatewayjava.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.sensitivedatagatewayjava.audit.AuditService;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.AuditRecord;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayErrorCode;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.ProcessedData;
import com.github.highcumontoa.sensitivedatagatewayjava.engine.DataProcessingEngine;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.AccessPolicy;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.PolicyRegistry;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.VersionSnapshotResolver;
import com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 默认网关门面。
 * <p>编排顺序（fail-closed）：
 * <ol>
 *   <li>一次性解析版本快照：默认最新；显式指定“已发布但非当前”版本 ->
 *       POLICY_VERSION_FALLBACK_REJECTED；指定“从未发布”的版本 -> POLICY_NOT_FOUND；</li>
 *   <li>分级在请求开始时取最新并以快照固定；</li>
 *   <li>引擎处理得到数据或可区分拒绝原因；</li>
 *   <li>放行与拒绝都写审计（含判定依据与版本）；审计写失败一律抛出 AUDIT_WRITE_FAILED，
 *       绝不出现“返回了数据却无痕迹”或“因审计异常静默放行”。</li>
 * </ol>
 * 历史审计记录自带发生时的策略版本，可按该版本复盘；不允许通过请求回退重放。
 */
@Component
public class DefaultSensitiveDataGateway implements SensitiveDataGateway {

    private static final Logger log = LoggerFactory.getLogger(DefaultSensitiveDataGateway.class);

    private final PolicyRegistry policyRegistry;
    private final DataProcessingEngine engine;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final VersionSnapshotResolver versionResolver;

    public DefaultSensitiveDataGateway(PolicyRegistry policyRegistry,
                                       DataProcessingEngine engine,
                                       AuditService auditService,
                                       ObjectMapper objectMapper,
                                       VersionSnapshotResolver versionResolver) {
        this.policyRegistry = policyRegistry;
        this.engine = engine;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.versionResolver = versionResolver;
    }

    @Override
    public ProcessedData access(AccessRequest request) {
        validate(request);
        String requestHash = com.github.highcumontoa.sensitivedatagatewayjava.audit.DefaultAuditService
                .sha256(stablePayload(request));
        log.info("ACCESS request raw caller={} purpose={} requestedPaths={} policyVersion={} payload={}",
                request.callerId(), request.purpose(), request.requestedPaths(),
                request.policyVersion(), stablePayload(request));

        // 一次性读取版本快照：整个请求期间使用同一不可变快照，不受并发发布影响
        VersionSnapshotResolver.Snapshot snapshot;
        try {
            snapshot = versionResolver.resolve(request.policyVersion());
        } catch (GatewayException ge) {
            AuditRecord rec = auditService.record(request,
                    request.policyVersion() == null || request.policyVersion().isBlank()
                            ? safeLatestPolicyVersion() : request.policyVersion(),
                    safeClassificationVersion(), false, ge.getCode(),
                    ge.getMessage() + " | " + request.callerId() + "/" + request.purpose(),
                    java.util.List.of(), requestHash);
            ge.setAuditId(rec.auditId());
            throw ge;
        }
        AccessPolicy policy = snapshot.policy();
        ClassificationDefinition classification = snapshot.classification();

        try {
            DataProcessingEngine.Result result = engine.process(request, policy, classification);
            AuditRecord record = auditService.record(request, policy.version(), classification.version(),
                    true, null, result.decisionBasis(), result.fieldResults(), requestHash);
            return new ProcessedData(record.auditId(), policy.version(), classification.version(),
                    result.data(), result.fieldResults());
        } catch (GatewayException ge) {
            AuditRecord denied = auditService.record(request, policy.version(),
                    classification.version(), false, ge.getCode(),
                    ge.getMessage() + " | " + request.callerId() + "/" + request.purpose(),
                    java.util.List.of(), requestHash);
            ge.setAuditId(denied.auditId());
            throw ge;
        } catch (Exception e) {
            // 底层异常归一化：不向调用方暴露内部细节，仍尽力留下审计痕迹
            GatewayException normalized;
            try {
                AuditRecord denied = auditService.record(request, policy.version(),
                        classification.version(), false, GatewayErrorCode.INTERNAL_ERROR,
                        "unexpected failure for " + request.callerId()
                                + "/" + request.purpose() + ": " + e.getClass().getSimpleName(),
                        java.util.List.of(), requestHash);
                normalized = new GatewayException(GatewayErrorCode.INTERNAL_ERROR,
                        "request could not be processed", e);
                normalized.setAuditId(denied.auditId());
            } catch (Exception auditFailure) {
                log.error("failed to write audit for unexpected failure", auditFailure);
                normalized = new GatewayException(GatewayErrorCode.INTERNAL_ERROR,
                        "request could not be processed", e);
            }
            throw normalized;
        }
    }

    private String safeClassificationVersion() {
        try {
            return versionResolver.resolve(null).classification().version();
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private String safeLatestPolicyVersion() {
        try {
            return policyRegistry.latestVersion();
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private void validate(AccessRequest request) {
        if (request == null) {
            throw new GatewayException(GatewayErrorCode.BAD_REQUEST, "request required");
        }
        if (request.callerId() == null || request.callerId().isBlank()) {
            throw new GatewayException(GatewayErrorCode.BAD_REQUEST, "callerId required");
        }
        if (request.purpose() == null || request.purpose().isBlank()) {
            throw new GatewayException(GatewayErrorCode.BAD_REQUEST, "purpose required");
        }
    }

    private String stablePayload(AccessRequest request) {
        try {
            return objectMapper.writeValueAsString(request.payload());
        } catch (JsonProcessingException e) {
            throw new GatewayException(GatewayErrorCode.DATA_MALFORMED,
                    "payload cannot be serialized for audit", e);
        }
    }
}
