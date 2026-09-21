package com.github.highcumontoa.sensitivedatagatewayjava.model;

/**
 * 放行字段允许的处理方式。MASK/REDACT 可逆推但不可逆还原，
 * TOKENIZE 为不可逆转换，输出必须显式标注。
 */
public enum TransformType {
    NONE,
    MASK,
    REDACT,
    TOKENIZE
}
