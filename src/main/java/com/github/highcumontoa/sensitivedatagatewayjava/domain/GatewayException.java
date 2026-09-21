package com.github.highcumontoa.sensitivedatagatewayjava.domain;

/**
 * 网关统一业务异常：携带可区分错误码与对外安全信息，不暴露内部细节。
 */
public class GatewayException extends RuntimeException {

    private final GatewayErrorCode code;

    public GatewayException(GatewayErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public GatewayException(GatewayErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public GatewayErrorCode getCode() {
        return code;
    }
}
