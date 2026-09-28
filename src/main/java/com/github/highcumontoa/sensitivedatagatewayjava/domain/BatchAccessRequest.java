package com.github.highcumontoa.sensitivedatagatewayjava.domain;

import java.util.List;

/**
 * 批量访问请求：整批共用同一个调用方、用途与可选的版本要求。
 * <p>{@code records} 中的每个元素为一条业务记录（通常为 JSON 对象，可包含多层
 * 数组与对象）。整批按全有或全无（all-or-nothing）语义处理。
 */
public record BatchAccessRequest(
        String callerId,
        String purpose,
        List<String> requestedPaths,
        String policyVersion,
        String classificationVersion,
        List<Object> records
) {
}
