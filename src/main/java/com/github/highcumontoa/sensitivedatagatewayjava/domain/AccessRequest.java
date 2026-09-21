package com.github.highcumontoa.sensitivedatagatewayjava.domain;

import java.util.List;

/**
 * 访问请求：调用方、用途、请求的敏感字段路径、显式指定的策略版本（可空）与数据负载。
 */
public record AccessRequest(
        String callerId,
        String purpose,
        List<String> requestedPaths,
        String policyVersion,
        Object payload
) {
}
