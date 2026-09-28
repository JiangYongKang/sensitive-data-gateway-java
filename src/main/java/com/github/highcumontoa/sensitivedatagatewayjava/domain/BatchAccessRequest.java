package com.github.highcumontoa.sensitivedatagatewayjava.domain;

import java.util.List;

/**
 * 批量访问请求：整批共用同一个调用方、用途与可选的版本要求；
 * records 为多条业务记录，每条记录可以是对象，也可以包含多层数组和对象。
 */
public record BatchAccessRequest(
        String callerId,
        String purpose,
        List<String> requestedPaths,
        String policyVersion,
        List<Object> records
) {
}
