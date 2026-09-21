package com.github.highcumontoa.sensitivedatagatewayjava.classification;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.SensitivityLevel;

/**
 * 字段识别与分级查询，基于指定版本的分级快照。
 */
public interface ClassificationService {

    SensitivityLevel classify(ClassificationDefinition definition, String canonicalPath);

    ClassificationDefinition current();
}
