package com.github.highcumontoa.sensitivedatagatewayjava.domain;

import java.util.List;

/**
 * 批量访问结果：整批共用一个审计ID与版本；data 与输入 records 一一对应，
 * 保持原记录顺序、层级、数组元素个数和原有类型；fieldResults 可按记录序号还原。
 */
public record BatchProcessedData(
        String auditId,
        String policyVersion,
        String classificationVersion,
        List<Object> data,
        List<RecordFieldResult> fieldResults
) {
}
