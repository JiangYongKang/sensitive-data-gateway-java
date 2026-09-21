package com.github.highcumontoa.sensitivedatagatewayjava.model;

/**
 * 拒绝响应：可区分的原因码（绝不返回空结果掩盖拒绝）。
 */
public record DenyResponse(
        String auditId,
        String policyVersion,
        DecisionCode code,
        String reason,
        boolean audited) {
}
