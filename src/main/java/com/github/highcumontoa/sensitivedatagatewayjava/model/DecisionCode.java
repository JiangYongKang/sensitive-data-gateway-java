package com.github.highcumontoa.sensitivedatagatewayjava.model;

/**
 * 判定结果码。拒绝类原因彼此可区分，映射稳定的 HTTP 状态。
 */
public enum DecisionCode {
    ALLOW(200),
    UNAUTHORIZED_CALLER(403),
    PURPOSE_MISMATCH(403),
    POLICY_MISSING(403),
    FIELD_LEVEL_EXCEEDED(403),
    POLICY_FALLBACK_DENIED(409),
    FIELD_MISSING(422),
    CLASSIFICATION_UNDEFINED(422),
    MALFORMED_DATA(422),
    LIMIT_PAYLOAD_TOO_LARGE(413),
    LIMIT_DEPTH_EXCEEDED(422),
    LIMIT_TIMEOUT(408),
    DOWNSTREAM_FAILURE(502),
    AUDIT_WRITE_FAILED(500),
    BAD_REQUEST(400),
    INTERNAL_ERROR(500);

    private final int httpStatus;

    DecisionCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public boolean isAllow() {
        return this == ALLOW;
    }
}
