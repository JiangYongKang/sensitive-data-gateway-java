package com.github.highcumontoa.sensitivedatagatewayjava.web.dto;

/**
 * 归一化对外错误体：不暴露内部细节。
 * {@code details} 为可选的结构化定位信息（如批量场景的记录序号与字段路径），无则为 null。
 */
public record ErrorResponse(String error, String code, String message, String auditId,
                            Object details) {
    public ErrorResponse(String error, String code, String message, String auditId) {
        this(error, code, message, auditId, null);
    }
}
