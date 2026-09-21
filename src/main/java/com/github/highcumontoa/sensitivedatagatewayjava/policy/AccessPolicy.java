package com.github.highcumontoa.sensitivedatagatewayjava.policy;

import java.util.Map;

/**
 * 访问策略（带版本）：callerId -> 授权；未列出的调用方一律未授权。
 */
public record AccessPolicy(
        String version,
        Map<String, Grant> grants
) {
}
