package com.github.highcumontoa.sensitivedatagatewayjava.transform;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.TransformType;

/**
 * 按转换类型、策略版本与分级字段键构造稳定的转换器。
 */
public interface TransformerFactory {

    /**
     * @param fieldKey 命中的分级字段键（分级定义中的键），而非字段在记录内的出现路径；
     *                 同一字段键在任意层级/数组位置/记录中都派生出同一个转换器。
     */
    ValueTransformer create(TransformType type, String policyVersion, String fieldKey);
}
