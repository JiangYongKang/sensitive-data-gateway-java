package com.github.highcumontoa.sensitivedatagatewayjava.web.dto;

/**
 * 归一化对外错误体：不暴露内部细节。
 */
public record ErrorResponse(String error, String code, String message, String auditId) {
}
