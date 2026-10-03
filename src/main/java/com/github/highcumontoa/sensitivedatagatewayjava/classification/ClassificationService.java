package com.github.highcumontoa.sensitivedatagatewayjava.classification;

/**
 * 字段识别与分级查询，基于指定版本的分级快照。
 */
public interface ClassificationService {

    /**
     * 识别字段：返回等级与命中的分级字段键（精确路径键或裸字段名键）。
     * 未命中任何分级字段时返回 {@code null}；命中 {@code unclassified} 清单时
     * 抛出 CLASSIFICATION_UNDEFINED。
     */
    FieldClassification resolve(ClassificationDefinition definition, String canonicalPath);

    ClassificationDefinition current();
}
