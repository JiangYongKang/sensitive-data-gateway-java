package com.github.highcumontoa.sensitivedatagatewayjava.audit;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.AuditRecord;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.FieldResult;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayErrorCode;

import java.util.List;

/**
 * 审计服务：写入失败归一化为 AUDIT_WRITE_FAILED，不得静默。
 */
public interface AuditService {

    AuditRecord record(AccessRequest request, String policyVersion, String classificationVersion,
                       boolean allowed, GatewayErrorCode denyReason, String decisionBasis,
                       List<FieldResult> fields, String requestHash);

    /**
     * 写一条批次级审计记录（整批成功或整批拒绝各一条）。
     */
    AuditRecord recordBatch(String callerId, String purpose, String policyVersion,
                            String classificationVersion, boolean allowed,
                            GatewayErrorCode denyReason, String decisionBasis,
                            List<FieldResult> fields, String requestHash,
                            int recordCount, Integer recordIndex, String fieldPath);
}
