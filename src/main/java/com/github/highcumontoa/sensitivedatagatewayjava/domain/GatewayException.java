package com.github.highcumontoa.sensitivedatagatewayjava.domain;

/**
 * 网关统一业务异常：携带可区分错误码与对外安全信息，不暴露内部细节。
 * <p>批量场景下可携带 {@code recordIndex}（批内记录序号，从 0 开始）与
 * {@code fieldPath}（该记录内的字段位置），用于把整批拒绝精确定位到具体记录与字段；
 * 单条场景二者为 null。
 */
public class GatewayException extends RuntimeException {

    private final GatewayErrorCode code;
    private final Integer recordIndex;
    private final String fieldPath;

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
}
