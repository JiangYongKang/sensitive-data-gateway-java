package com.github.highcumontoa.sensitivedatagatewayjava.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.sensitivedatagatewayjava.audit.AuditService;
import com.github.highcumontoa.sensitivedatagatewayjava.audit.DefaultAuditService;
import com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationDefinition;
import com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationRegistry;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.AuditRecord;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.BatchAccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.BatchProcessedData;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayErrorCode;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.ProcessedData;
import com.github.highcumontoa.sensitivedatagatewayjava.engine.BatchDataProcessingEngine;
import com.github.highcumontoa.sensitivedatagatewayjava.engine.DataProcessingEngine;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.AccessPolicy;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.PolicyRegistry;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.VersionResolver;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.VersionSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认网关门面。
 * <p>编排顺序（fail-closed）：
 * <ol>
 *   <li>解析策略版本：默认最新；显式指定旧版本一律拒绝（POLICY_VERSION_FALLBACK_REJECTED），
 *       杜绝静默混用；不存在的版本 -> POLICY_NOT_FOUND；</li>
 *   <li>分级始终取最新版本进行识别与处理；</li>
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
    private final ClassificationRegistry classificationRegistry;
    private final VersionResolver versionResolver;
    private final DataProcessingEngine engine;
    private final BatchDataProcessingEngine batchEngine;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public DefaultSensitiveDataGateway(PolicyRegistry policyRegistry,
                                       ClassificationRegistry classificationRegistry,
                                       VersionResolver versionResolver,
                                       DataProcessingEngine engine,
                                       BatchDataProcessingEngine batchEngine,
                                       AuditService auditService,
                                       ObjectMapper objectMapper) {
        this.policyRegistry = policyRegistry;
        this.classificationRegistry = classificationRegistry;
        this.versionResolver = versionResolver;
        this.engine = engine;
        this.batchEngine = batchEngine;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ProcessedData access(AccessRequest request) {
        validate(request);
        String requestHash = DefaultAuditService.sha256(stablePayload(request));
        log.info("ACCESS request raw caller={} purpose={} requestedPaths={} policyVersion={} payload={}",
                request.callerId(), request.purpose(), request.requestedPaths(),
                request.policyVersion(), stablePayload(request));

        // 一次性解析版本快照：整个请求期间使用同一不可变快照，不受并发发布影响。
        // 从未发布 vs 已发布但非当前，由解析器给出可区分错误码；版本类拒绝也留审计。
        VersionSnapshot versions;
        try {
            versions = versionResolver.resolve(request.policyVersion(), null);
        } catch (GatewayException ve) {
            AuditRecord rec = auditService.record(request,
                    request.policyVersion(), safeClassificationVersion(), false,
                    ve.getCode(), ve.getMessage() + " | "
                            + request.callerId() + "/" + request.purpose(),
                    java.util.List.of(), requestHash);
            throw new GatewayException(ve.getCode(),
                    ve.getMessage() + "; auditId=" + rec.auditId());
        }
        AccessPolicy policy = versions.policy();
        ClassificationDefinition classification = versions.classification();

        try {
            DataProcessingEngine.Result result = engine.process(request, policy, classification);
            AuditRecord record = auditService.record(request, policy.version(), classification.version(),
                    true, null, result.decisionBasis(), result.fieldResults(), requestHash);
            return new ProcessedData(record.auditId(), policy.version(), classification.version(),
                    result.data(), result.fieldResults());
        } catch (GatewayException ge) {
            auditDenied(request, policy.version(), requestHash, ge.getCode(),
                    ge.getMessage() + " | " + request.callerId() + "/" + request.purpose());
            throw ge;
        } catch (Exception e) {
            // 底层异常归一化：不向调用方暴露内部细节，仍尽力留下审计痕迹
            GatewayException normalized = new GatewayException(GatewayErrorCode.INTERNAL_ERROR,
                    "request could not be processed", e);
            try {
                auditDenied(request, policy.version(), requestHash,
                        GatewayErrorCode.INTERNAL_ERROR,
                        "unexpected failure for " + request.callerId()
                                + "/" + request.purpose() + ": " + e.getClass().getSimpleName());
            } catch (Exception auditFailure) {
                log.error("failed to write audit for unexpected failure", auditFailure);
            }
            throw normalized;
        }
    }

    private AuditRecord auditDenied(AccessRequest request, String policyVersion, String requestHash,
                                    GatewayErrorCode reason, String basis) {
        return auditService.record(request, policyVersion,
                safeClassificationVersion(), false, reason, basis,
                java.util.List.of(), requestHash);
    }

    private String safeClassificationVersion() {
        try {
            return classificationRegistry.latestVersion();
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

    @Override
    public BatchProcessedData accessBatch(BatchAccessRequest request) {
        validateBatch(request);
        String requestHash = DefaultAuditService.sha256(stableBatchPayload(request));
        log.info("BATCH request raw caller={} purpose={} policyVersion={} classificationVersion={} "
                        + "recordCount={} payload={}",
                request.callerId(), request.purpose(), request.policyVersion(),
                request.classificationVersion(), request.records().size(),
                stableBatchPayload(request));

        // 整批一次性固定策略与分级快照：处理期间任何发布/授权变更都不影响本批
        VersionSnapshot versions;
        try {
            versions = versionResolver.resolve(request.policyVersion(), request.classificationVersion());
        } catch (GatewayException ve) {
            AuditRecord rec = auditService.recordBatch(request.callerId(), request.purpose(),
                    request.policyVersion(),
                    request.classificationVersion() == null
                            ? safeClassificationVersion() : request.classificationVersion(),
                    false, ve.getCode(),
                    ve.getMessage() + " | " + request.callerId() + "/" + request.purpose(),
                    List.of(), requestHash, request.records().size(), null, null);
            throw new GatewayException(ve.getCode(),
                    ve.getMessage() + "; auditId=" + rec.auditId());
        }
        AccessPolicy policy = versions.policy();
        ClassificationDefinition classification = versions.classification();

        try {
            BatchDataProcessingEngine.BatchResult result =
                    batchEngine.process(request, policy, classification);
            AuditRecord record = auditService.recordBatch(request.callerId(), request.purpose(),
                    policy.version(), classification.version(), true, null,
                    result.decisionBasis(), result.allFieldResults(), requestHash,
                    request.records().size(), null, null);
            return new BatchProcessedData(record.auditId(), policy.version(),
                    classification.version(), result.records().size(), result.records());
        } catch (GatewayException ge) {
            String basis = ge.getMessage() + " | " + request.callerId() + "/" + request.purpose();
            AuditRecord rec = auditService.recordBatch(request.callerId(), request.purpose(),
                    policy.version(), classification.version(), false, ge.getCode(), basis,
                    List.of(), requestHash, request.records().size(),
                    ge.getRecordIndex(), ge.getFieldPath());
            throw new GatewayException(ge.getCode(),
                    ge.getMessage() + "; auditId=" + rec.auditId(),
                    ge.getRecordIndex(), ge.getFieldPath());
        } catch (Exception e) {
            GatewayException normalized = new GatewayException(GatewayErrorCode.INTERNAL_ERROR,
                    "batch request could not be processed", e);
            try {
                auditService.recordBatch(request.callerId(), request.purpose(),
                        policy.version(), classification.version(), false,
                        GatewayErrorCode.INTERNAL_ERROR,
                        "unexpected batch failure for " + request.callerId()
                                + "/" + request.purpose() + ": " + e.getClass().getSimpleName(),
                        List.of(), requestHash, request.records().size(), null, null);
            } catch (Exception auditFailure) {
                log.error("failed to write audit for unexpected batch failure", auditFailure);
            }
            throw normalized;
        }
    }

    private void validateBatch(BatchAccessRequest request) {
        if (request == null) {
            throw new GatewayException(GatewayErrorCode.BAD_REQUEST, "request required");
        }
        if (request.callerId() == null || request.callerId().isBlank()) {
            throw new GatewayException(GatewayErrorCode.BAD_REQUEST, "callerId required");
        }
        if (request.purpose() == null || request.purpose().isBlank()) {
            throw new GatewayException(GatewayErrorCode.BAD_REQUEST, "purpose required");
        }
        if (request.records() == null || request.records().isEmpty()) {
            throw new GatewayException(GatewayErrorCode.BAD_REQUEST,
                    "records must contain at least one entry");
        }
    }

    private String stableBatchPayload(BatchAccessRequest request) {
        try {
            return objectMapper.writeValueAsString(request.records());
        } catch (JsonProcessingException e) {
            throw new GatewayException(GatewayErrorCode.DATA_MALFORMED,
                    "batch payload cannot be serialized for audit", e);
        }
    }
}
