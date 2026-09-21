package com.github.highcumontoa.sensitivedatagatewayjava.model;

/**
 * 对输出中某敏感字段处理方式的显式标注。
 *
 * @param fieldPath    字段路径
 * @param level        敏感等级
 * @param transform    处理方式
 * @param irreversible 是否不可逆（REDACT / TOKENIZE 为不可逆；MASK/NONE 为可逆推保留）
 */
public record FieldTransformMarker(
        String fieldPath,
        SensitivityLevel level,
        TransformType transform,
        boolean irreversible) {
}
