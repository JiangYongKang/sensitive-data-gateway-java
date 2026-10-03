package com.github.highcumontoa.sensitivedatagatewayjava.classification;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.SensitivityLevel;

/**
 * 一次字段识别的结果。
 *
 * @param fieldKey 命中的分级字段键（分级定义中的键：精确规范路径或裸字段名）。
 *                 同一原始值落在同一分级字段键下，无论出现在记录内哪一层、
 *                 哪个数组下标，都视为同一分级字段，用于派生稳定的令牌密钥。
 * @param level    该字段的敏感等级。
 */
public record Classification(String fieldKey, SensitivityLevel level) {
}
