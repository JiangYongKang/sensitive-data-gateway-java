package com.github.highcumontoa.sensitivedatagatewayjava.transform;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.TransformType;

/**
 * 按转换类型、策略版本与分级字段键构造稳定的转换器。
 *
 * @param fieldKey 命中的分级字段键（分级定义中的精确路径键或裸字段名键），
 *                 只标识“同一个分级字段”，不含嵌套层级/数组下标/记录序号/排序位置
 */
public interface TransformerFactory {

    ValueTransformer create(TransformType type, String policyVersion, String fieldKey);
}
