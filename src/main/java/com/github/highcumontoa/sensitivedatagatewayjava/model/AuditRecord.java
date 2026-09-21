package com.github.highcumontoa.sensitivedatagatewayjava.model;

import java.time.Instant;

/**
 * 审计记录：包含原始输入摘要、判定依据与所用策略版本。
 */
public record AuditRecord(
        String auditId,
        Instant timestamp,
        String callerId,
        String purpose,
        String requestedVersion,
        String policyVersion,
        String latestVersion,
        DecisionCode decisionCode,
        String reason,
        String inputJson,
        String basis) {
}
