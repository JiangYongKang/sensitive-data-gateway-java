package com.github.highcumontoa.sensitivedatagatewayjava.domain;

import java.util.List;

/**
 * 网关访问结果：审计ID、所用版本、处理后的数据与字段处理说明。
 */
public record ProcessedData(
        String auditId,
        String policyVersion,
        String classificationVersion,
        Object data,
        List<FieldResult> fieldResults
) {
}
