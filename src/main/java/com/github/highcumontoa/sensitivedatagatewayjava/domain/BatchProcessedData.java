package com.github.highcumontoa.sensitivedatagatewayjava.domain;

import java.util.List;

/**
 * 批量访问结果：整批成功时返回。
 * <p>输出保持与请求一致的记录顺序、数量；每条记录包含其处理后的数据与
 * 可定位到该记录内路径的字段处理说明。整批共用同一审计ID与版本。
 */
public record BatchProcessedData(
        String auditId,
        String policyVersion,
        String classificationVersion,
        int recordCount,
        List<ProcessedRecord> records
) {
}
