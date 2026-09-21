package com.github.highcumontoa.sensitivedatagatewayjava.domain;

/**
 * 转换类型：
 * NONE 放行原值（可逆）；
 * MASK 掩码（不可逆）；
 * REDACT 脱敏（不可逆）；
 * TOKENIZE 令牌化（不可逆）。
 */
public enum TransformType {
    NONE,
    MASK,
    REDACT,
    TOKENIZE
}
