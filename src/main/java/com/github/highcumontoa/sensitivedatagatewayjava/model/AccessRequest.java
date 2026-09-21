package com.github.highcumontoa.sensitivedatagatewayjava.model;

import java.util.Map;

/**
 * 网关访问请求。
 */
public record AccessRequest(
        String callerId,
        String purpose,
        String policyVersion,
        Map<String, Object> payload) {
}
