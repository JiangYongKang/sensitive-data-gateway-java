package com.github.highcumontoa.sensitivedatagatewayjava.transform;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.TransformType;

/**
 * 按转换类型、策略版本与字段路径构造稳定的转换器。
 */
public interface TransformerFactory {

    ValueTransformer create(TransformType type, String policyVersion, String fieldPath);
}
