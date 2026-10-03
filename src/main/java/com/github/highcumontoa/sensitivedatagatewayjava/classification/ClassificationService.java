package com.github.highcumontoa.sensitivedatagatewayjava.classification;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.SensitivityLevel;

/**
 * 字段识别与分级查询，基于指定版本的分级快照。
 */
public interface ClassificationService {

    /**
     * 识别字段并返回命中的分级字段键与等级；未命中分级返回 {@code null}。
     * 命中 {@code unclassified} 清单时抛出 CLASSIFICATION_UNDEFINED。
     */
    Classification match(ClassificationDefinition definition, String canonicalPath);

    default SensitivityLevel classify(ClassificationDefinition definition, String canonicalPath) {
        Classification matched = match(definition, canonicalPath);
        return matched == null ? null : matched.level();
    }

    ClassificationDefinition current();
}
