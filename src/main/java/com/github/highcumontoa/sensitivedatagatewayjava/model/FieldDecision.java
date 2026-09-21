package com.github.highcumontoa.sensitivedatagatewayjava.model;

/**
 * 单个敏感字段的判定结论。
 */
public record FieldDecision(
        String fieldPath,
        DecisionCode code,
        SensitivityLevel level,
        TransformType transform,
        String reason) {
}
