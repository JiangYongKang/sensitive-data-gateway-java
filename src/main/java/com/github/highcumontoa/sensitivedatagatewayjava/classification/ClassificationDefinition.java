package com.github.highcumontoa.sensitivedatagatewayjava.classification;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.SensitivityLevel;

import java.util.List;
import java.util.Map;

/**
 * 分级定义（带版本，发布后不可变）。
 * <ul>
 *   <li>{@code fieldLevels}：敏感字段键 -> 等级。键可为规范路径（{@code person.ssn}）或裸字段名（{@code ssn}）。
 *       未列入的普通字段视为非敏感字段，原样透传。</li>
 *   <li>{@code unclassified}：已知为敏感数据、但分级尚未定义的字段键。
 *       数据中一旦出现这些字段，请求以 CLASSIFICATION_UNDEFINED 被拒绝，绝不静默跳过。</li>
 *   <li>{@code required}：必须出现的敏感字段规范路径（或裸字段名）；缺失以 FIELD_MISSING 拒绝。</li>
 * </ul>
 */
public record ClassificationDefinition(
        String version,
        Map<String, SensitivityLevel> fieldLevels,
        List<String> unclassified,
        List<String> required
) {
}
