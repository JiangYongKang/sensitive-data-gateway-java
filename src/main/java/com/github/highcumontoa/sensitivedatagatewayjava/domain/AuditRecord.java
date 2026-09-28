package com.github.highcumontoa.sensitivedatagatewayjava.domain;

import java.time.Instant;
import java.util.List;

/**
 * 审计记录：包含判定依据与所用策略/分级版本。
 * <p>批量请求写一条批次级记录：{@code batch=true}、{@code recordCount} 为批内记录数；
 * 成功时 {@code fields} 中每个字段说明的 path 带 {@code records[i].} 前缀，可还原到具体记录。
 * 单条请求 {@code batch=false}、{@code recordCount}=1、{@code recordIndex}=null。
 */
public record AuditRecord(
        String auditId,
        Instant timestamp,
        String callerId,
        String purpose,
        String policyVersion,
        String classificationVersion,
        boolean allowed,
        GatewayErrorCode denyReason,
        String decisionBasis,
        List<FieldResult> fields,
        String requestHash,
        boolean batch,
        Integer recordCount,
        Integer recordIndex,
        String fieldPath
) {
}
