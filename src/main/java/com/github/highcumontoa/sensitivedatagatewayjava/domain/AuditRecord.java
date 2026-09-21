package com.github.highcumontoa.sensitivedatagatewayjava.domain;

import java.time.Instant;
import java.util.List;

/**
 * 审计记录：包含判定依据与所用策略/分级版本。
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
        String requestHash
) {
}
