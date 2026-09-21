package com.github.highcumontoa.sensitivedatagatewayjava.web;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayErrorCode;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.web.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 对外异常归一化：只返回错误码与安全信息，不泄漏堆栈或内部细节。
 */
@RestControllerAdvice
public class GatewayExceptionHandler {

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErrorResponse> handleGateway(GatewayException ex) {
        return ResponseEntity.status(statusFor(ex.getCode()))
                .body(new ErrorResponse("sensitive_data_gateway_error",
                        ex.getCode().name(), ex.getMessage(), null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("sensitive_data_gateway_error",
                        GatewayErrorCode.BAD_REQUEST.name(), "request body is malformed JSON", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("sensitive_data_gateway_error",
                        GatewayErrorCode.INTERNAL_ERROR.name(),
                        "request could not be processed", null));
    }

    private HttpStatus statusFor(GatewayErrorCode code) {
        return switch (code) {
            case UNAUTHORIZED_CALLER, PURPOSE_MISMATCH, LEVEL_NOT_GRANTED, FIELD_MISSING,
                 CLASSIFICATION_UNDEFINED, POLICY_VERSION_FALLBACK_REJECTED -> HttpStatus.FORBIDDEN;
            case POLICY_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DEPTH_LIMIT_EXCEEDED, SIZE_LIMIT_EXCEEDED, TIMEOUT_EXCEEDED,
                 DATA_MALFORMED, BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case DOWNSTREAM_FAILURE, AUDIT_WRITE_FAILED, INTERNAL_ERROR ->
                    HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
