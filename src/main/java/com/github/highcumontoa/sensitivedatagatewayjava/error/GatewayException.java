package com.github.highcumontoa.sensitivedatagatewayjava.error;

import com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode;

/**
 * 网关统一对外异常：携带可区分的判定码与不暴露内部细节的对外消息。
 */
public class GatewayException extends RuntimeException {

    private final DecisionCode code;

    public GatewayException(DecisionCode code, String publicMessage) {
        super(publicMessage);
        this.code = code;
    }

    public GatewayException(DecisionCode code, String publicMessage, Throwable cause) {
        super(publicMessage, cause);
        this.code = code;
    }

    public DecisionCode getCode() {
        return code;
    }
}
