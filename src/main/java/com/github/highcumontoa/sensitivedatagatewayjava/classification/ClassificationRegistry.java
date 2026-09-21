package com.github.highcumontoa.sensitivedatagatewayjava.classification;

import com.github.highcumontoa.sensitivedatagatewayjava.model.ClassificationDefinition;

import java.util.List;
import java.util.Optional;

/**
 * 字段分级注册表（随策略版本不可变）。
 */
public interface ClassificationRegistry {

    /**
     * 按归一化路径精确/通配查找分级定义。
     */
    Optional<ClassificationDefinition> lookup(String normalizedPath);

    /**
     * 是否存在任何分级定义（无定义时数据访问必须显式拒绝，不得默认放行）。
     */
    boolean isEmpty();

    /**
     * 全部定义（供诊断/审计）。
     */
    List<ClassificationDefinition> definitions();
}
