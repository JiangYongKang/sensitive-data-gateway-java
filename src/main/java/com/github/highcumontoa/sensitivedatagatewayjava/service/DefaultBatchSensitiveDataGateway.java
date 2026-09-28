package com.github.highcumontoa.sensitivedatagatewayjava.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.sensitivedatagatewayjava.audit.AuditService;
import com.github.highcumontoa.sensitivedatagatewayjava.audit.DefaultAuditService;
import com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationDefinition;
import com.github.highcumontoa.sensitivedatagatewayjava.config.GatewayProperties;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.AuditRecord;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.BatchAccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.BatchProcessedData;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.FieldResult;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayErrorCode;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.RecordFieldResult;
import com.github.highcumontoa.sensitivedatagatewayjava.engine.DataProcessingEngine;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.AccessPolicy;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.VersionSnapshotResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认批量网关门面。
 * <p>语义：
 * <ol>
 *   <li>整批共用调用方、用途与一次取得的不可变版本快照；并发发布/授权变更不影响在途批次；</li>
 *   <li>任何转换之前先做批次级（记录数）与逐记录（对象契约、深度、整批规模、必备字段）预检，
 *       超限在处理前拒绝；</li>
 *   <li>随后按输入顺序逐条处理：任一记录失败立即中止，整批拒绝（不返回任何已处理数据），
 *       失败定位到记录序号与记录内字段路径，错误码区分数据/权限/用途/批次限制；</li>
 *   <li>全部成功才组装与输入同序、同层级、同数组长度、同类型族的结果，并写一条整批审计；</li>
 *   <li>放行与拒绝都写整批审计（含记录数、版本与按记录序号的字段说明）；审计失败 fail-closed。</li>
 * </ol>
 */
@Component
public class DefaultBatchSensitiveDataGateway implements BatchSensitiveDataGateway {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultBatchSensitiveDataGateway.class);

    private final DataProcessingEngine engine;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final VersionSnapshotResolver versionResolver;
    private final GatewayProperties properties;

    public DefaultBatchSensitiveDataGateway(DataProcessingEngine engine,
                                            AuditService auditService,
                                            ObjectMapper objectMapper,
                                            VersionSnapshotResolver versionResolver,
                                            GatewayProperties properties) {
        this.engine = engine;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.versionResolver = versionResolver;
        this.properties = properties;
    }

    @Override
    public BatchProcessedData accessBatch(BatchAccessRequest request) {
        validate(request);
        String requestHash = DefaultAuditService.sha256(stablePayload(request.records()));
        log.info("BATCH request raw caller={} purpose={} recordCount={} policyVersion={}",
                request.callerId(), request.purpose(), request.records().size(),
                request.policyVersion());
        long startNanos = System.nanoTime();

        VersionSnapshotResolver.Snapshot snapshot;
        try {
            snapshot = versionResolver.resolve(request.policyVersion());
        } catch (GatewayException ge) {
            AuditRecord rec = auditService.recordBatch(request.callerId(), request.purpose(),
                    effectiveVersionForAudit(request.policyVersion()),
                    safeClassificationVersion(), false, ge.getCode(),
                    ge.getMessage() + " | " + request.callerId() + "/" + request.purpose(),
                    request.records().size(), List.of(), requestHash);
            ge.setAuditId(rec.auditId());
            throw ge;
        }
        AccessPolicy policy = snapshot.policy();
        ClassificationDefinition classification = snapshot.classification();
        String fixedClassificationVersion = classification.version();

        // 逐字段判定共用同一个访问请求（调用方/用途一致）
        AccessRequest single = new AccessRequest(request.callerId(), request.purpose(),
                request.requestedPaths() == null ? List.of()
                        : List.copyOf(request.requestedPaths()),
                request.policyVersion(), null);

        try {
            // 处理前预检：批次记录数 + 逐记录对象契约/深度/整批规模/必备字段/耗时
            if (request.records().size() > properties.getMaxBatchRecords()) {
                throw new GatewayException(GatewayErrorCode.BATCH_SIZE_LIMIT_EXCEEDED,
                        "batch record count " + request.records().size()
                                + " exceeds limit " + properties.getMaxBatchRecords());
            }
            engine.preflightBatch(request.records(), single, policy, classification,
                    properties.getMaxBatchRecordDepth(),
                    properties.getMaxBatchTotalNodes(), startNanos);

            List<Object> output = new ArrayList<>(request.records().size());
            List<RecordFieldResult> batchFields = new ArrayList<>();
            List<String> basis = new ArrayList<>();
            for (int i = 0; i < request.records().size(); i++) {
                DataProcessingEngine.RecordResult rr = engine.processBatchRecord(
                        request.records(), i, single, policy, classification, startNanos);
                output.add(rr.data());
                if (rr.decisionBasis() != null && !rr.decisionBasis().isBlank()) {
                    basis.add("record[" + i + "] " + rr.decisionBasis());
                }
                for (FieldResult fr : rr.fieldResults()) {
                    batchFields.add(new RecordFieldResult(i, fr));
                }
            }

            String basisText = String.join(" | ", basis);
            AuditRecord record = auditService.recordBatch(request.callerId(), request.purpose(),
                    policy.version(), classification.version(), true, null, basisText,
                    request.records().size(), batchFields, requestHash);
            return new BatchProcessedData(record.auditId(), policy.version(),
                    classification.version(), List.copyOf(output), List.copyOf(batchFields));
        } catch (GatewayException ge) {
            // 全有或全无：任何失败都不返回已处理数据，仅写一条整批拒绝审计
            AuditRecord denied = auditService.recordBatch(request.callerId(), request.purpose(),
                    policy.version(), fixedClassificationVersion, false, ge.getCode(),
                    ge.getMessage() + " | " + request.callerId() + "/" + request.purpose(),
                    request.records().size(), List.of(), requestHash);
            ge.setAuditId(denied.auditId());
            throw ge;
        } catch (Exception e) {
            log.error("unexpected batch failure", e);
            AuditRecord denied = auditService.recordBatch(request.callerId(), request.purpose(),
                    policy.version(), fixedClassificationVersion, false,
                    GatewayErrorCode.INTERNAL_ERROR,
                    "unexpected batch failure for " + request.callerId()
                            + "/" + request.purpose() + ": " + e.getClass().getSimpleName(),
                    request.records().size(), List.of(), requestHash);
            GatewayException normalized = new GatewayException(GatewayErrorCode.INTERNAL_ERROR,
                    "batch request could not be processed", e);
            normalized.setAuditId(denied.auditId());
            throw normalized;
        }
    }

    private String effectiveVersionForAudit(String requested) {
        if (requested == null || requested.isBlank()) {
            try {
                return versionResolver.resolve(null).policy().version();
            } catch (Exception e) {
                return "unavailable";
            }
        }
        return requested;
    }

    private String safeClassificationVersion() {
        try {
            return versionResolver.resolve(null).classification().version();
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private void validate(BatchAccessRequest request) {
        if (request == null) {
            throw new GatewayException(GatewayErrorCode.BAD_REQUEST, "request required");
        }
        if (request.callerId() == null || request.callerId().isBlank()) {
            throw new GatewayException(GatewayErrorCode.BAD_REQUEST, "callerId required");
        }
        if (request.purpose() == null || request.purpose().isBlank()) {
            throw new GatewayException(GatewayErrorCode.BAD_REQUEST, "purpose required");
        }
        if (request.records() == null) {
            throw new GatewayException(GatewayErrorCode.BAD_REQUEST, "records required");
        }
        if (request.records().isEmpty()) {
            throw new GatewayException(GatewayErrorCode.BAD_REQUEST,
                    "records must contain at least one record");
        }
    }

    private String stablePayload(List<Object> records) {
        try {
            return objectMapper.writeValueAsString(records);
        } catch (JsonProcessingException e) {
            throw new GatewayException(GatewayErrorCode.DATA_MALFORMED,
                    "records cannot be serialized for audit", e);
        }
    }
}
