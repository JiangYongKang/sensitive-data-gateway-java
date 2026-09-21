package com.github.highcumontoa.sensitivedatagatewayjava.transform;

/**
 * 值转换器：同一策略下同一原始值输出必须稳定。
 */
public interface ValueTransformer {

    Object apply(Object original);

    boolean reversible();

    com.github.highcumontoa.sensitivedatagatewayjava.domain.TransformType type();
}
