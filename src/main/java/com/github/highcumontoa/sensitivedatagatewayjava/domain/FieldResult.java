package com.github.highcumontoa.sensitivedatagatewayjava.domain;

/**
 * 单个敏感字段的处理结果（供审计与响应说明）。
 */
public record FieldResult(
        String path,
        SensitivityLevel level,
        TransformType transform,
        boolean reversible,
        boolean transformed
) {
}
