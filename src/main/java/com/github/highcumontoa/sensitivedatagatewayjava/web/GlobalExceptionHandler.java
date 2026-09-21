package com.github.highcumontoa.sensitivedatagatewayjava.web;

import com.github.highcumontoa.sensitivedatagatewayjava.error.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 归一化对外错误：只暴露稳定的原因码与安全消息，内部异常不外泄堆栈/细节。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<Map<String, Object>> handleGateway(GatewayException ex) {
        DecisionCode code = ex.getCode();
        if (code.getHttpStatus() >= 500) {
            log.error("网关内部错误 code={} message={}", code, ex.getMessage(), ex);
        } else {
            log.warn("请求被拒绝 code={} message={}", code, ex.getMessage());
        }
        return ResponseEntity.status(code.getHttpStatus()).body(Map.of(
                "code", code.name(),
                "reason", safeMessage(ex.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception ex) {
        // 不把底层细节（类名、堆栈、下游信息）回传给调用方
        log.error("未归一化异常，转换为 DOWNSTREAM_FAILURE", ex);
        return ResponseEntity.status(DecisionCode.DOWNSTREAM_FAILURE.getHttpStatus()).body(Map.of(
                "code", DecisionCode.DOWNSTREAM_FAILURE.name(),
                "reason", "下游处理失败，请求未产生数据结果"));
    }

    private String safeMessage(String message) {
        return message == null ? "请求被拒绝" : message;
    }
}
