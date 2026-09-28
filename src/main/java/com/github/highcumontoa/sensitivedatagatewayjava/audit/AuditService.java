package com.github.highcumontoa.sensitivedatagatewayjava.audit;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.AuditRecord;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.FieldResult;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayErrorCode;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.RecordFieldResult;

import java.util.List;

/**
 * 审计服务：写入失败归一化为 AUDIT_WRITE_FAILED，不得静默。
 */
public interface AuditService {

    AuditRecord record(AccessRequest request, String policyVersion, String classificationVersion,
                       boolean allowed, GatewayErrorCode denyReason, String decisionBasis,
                       List<FieldResult> fields, String requestHash);

    /**
     * 批量请求审计：固化整批所用版本、记录数与按记录序号还原的字段说明。
     */
    AuditRecord recordBatch(String callerId, String purpose,
                            String policyVersion, String classificationVersion,
                            boolean allowed, GatewayErrorCode denyReason, String decisionBasis,
                            int recordCount, List<RecordFieldResult> batchFields,
                            String requestHash);
}
