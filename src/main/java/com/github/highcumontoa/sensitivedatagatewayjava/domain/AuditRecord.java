package com.github.highcumontoa.sensitivedatagatewayjava.domain;

import java.time.Instant;
import java.util.List;

/**
 * 审计记录：包含判定依据与所用策略/分级版本。
 * <p>批量请求额外携带 {@code batch} 标记、{@code recordCount} 与按记录序号还原的
 * 字段说明 {@code batchFields}；单条请求这些字段为 null/false，保持原结构。
 */
public record AuditRecord(
        String auditId,
        Instant timestamp,
        String callerId,
        String purpose,
        String policyVersion,
        String classificationVersion,
        boolean allowed,
        GatewayErrorCode denyReason,
        String decisionBasis,
        List<FieldResult> fields,
        String requestHash,
        boolean batch,
        Integer recordCount,
        List<RecordFieldResult> batchFields
) {
    /**
     * 单条请求的审计记录（保持原签名）。
     */
    public AuditRecord(String auditId, Instant timestamp, String callerId, String purpose,
                       String policyVersion, String classificationVersion, boolean allowed,
                       GatewayErrorCode denyReason, String decisionBasis,
                       List<FieldResult> fields, String requestHash) {
        this(auditId, timestamp, callerId, purpose, policyVersion, classificationVersion,
                allowed, denyReason, decisionBasis, fields, requestHash, false, null, null);
    }
}
