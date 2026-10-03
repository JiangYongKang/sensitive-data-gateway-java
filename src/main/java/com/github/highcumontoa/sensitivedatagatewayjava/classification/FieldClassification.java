package com.github.highcumontoa.sensitivedatagatewayjava.classification;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.SensitivityLevel;

/**
 * 字段识别结果：命中的分级等级，以及命中所依据的分级字段键。
 * <p>{@code fieldKey} 是分级定义 {@code fieldLevels} 中实际命中的键
 * （精确规范路径键或裸字段名键），只标识“同一个分级字段”，
 * 不含记录序号、嵌套层级与数组下标，用作令牌密钥派生的字段域。
 */
public record FieldClassification(SensitivityLevel level, String fieldKey) {
}
