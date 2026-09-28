package com.github.highcumontoa.sensitivedatagatewayjava.domain;

/**
 * 网关统一业务异常：携带可区分错误码与对外安全信息，不暴露内部细节。
 * 批量场景下可附带记录序号（{@code recordIndex}，从 0 开始）与记录内字段路径
 * （{@code fieldPath}），用于把整批拒绝定位到具体记录和字段；单条场景二者为 null。
 * {@code auditId} 在写完拒绝审计后由门面回填，便于调用方凭错误体追溯审计。
 */
public class GatewayException extends RuntimeException {

    private final GatewayErrorCode code;
    private final Integer recordIndex;
    private final String fieldPath;
    private String auditId;

    public GatewayException(GatewayErrorCode code, String message) {
        this(code, message, null, null, null);
    }

    public GatewayException(GatewayErrorCode code, String message, Throwable cause) {
        this(code, message, null, null, cause);
    }

    public GatewayException(GatewayErrorCode code, String message,
                            Integer recordIndex, String fieldPath) {
        this(code, message, recordIndex, fieldPath, null);
    }

    public GatewayException(GatewayErrorCode code, String message,
                            Integer recordIndex, String fieldPath, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.recordIndex = recordIndex;
        this.fieldPath = fieldPath;
    }

    public GatewayErrorCode getCode() {
        return code;
    }

    public Integer getRecordIndex() {
        return recordIndex;
    }

    public String getFieldPath() {
        return fieldPath;
    }

    public String getAuditId() {
        return auditId;
    }

    public void setAuditId(String auditId) {
        this.auditId = auditId;
    }
}
