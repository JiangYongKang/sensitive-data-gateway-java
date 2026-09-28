package com.github.highcumontoa.sensitivedatagatewayjava.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 归一化对外错误体：不暴露内部细节。
 * <p>批量拒绝时额外携带：
 * <ul>
 *   <li>{@code recordIndex}：失败记录在批内的序号（从 0 开始）；批次级原因为 null；</li>
 *   <li>{@code fieldPath}：失败记录内的字段位置；</li>
 *   <li>{@code category}：原因分类 DATA / AUTHORIZATION / LIMIT / VERSION / INTERNAL，
 *       便于调用方区分数据问题、权限用途不通过与批次/深度/规模限制。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String error,
        String code,
        String message,
        String auditId,
        Integer recordIndex,
        String fieldPath,
        String category
) {
    public ErrorResponse(String error, String code, String message, String auditId) {
        this(error, code, message, auditId, null, null, null);
    }
}
