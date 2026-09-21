package com.github.highcumontoa.sensitivedatagatewayjava.model;

import java.util.List;

/**
 * 分级注册表的单条定义：字段路径（点分，数组元素用 [*]）→ 敏感等级。
 *
 * @param path       字段路径
 * @param level      敏感等级
 * @param required   该字段是否必须存在；缺失时拒绝而非静默跳过
 * @param valueTypes 允许的 Java 值类型简单名（空表示不约束）
 */
public record ClassificationDefinition(
        String path,
        SensitivityLevel level,
        boolean required,
        List<String> valueTypes) {
}
