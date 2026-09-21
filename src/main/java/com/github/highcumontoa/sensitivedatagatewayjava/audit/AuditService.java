package com.github.highcumontoa.sensitivedatagatewayjava.audit;

import com.github.highcumontoa.sensitivedatagatewayjava.model.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.model.AuditRecord;
import com.github.highcumontoa.sensitivedatagatewayjava.model.EvaluationResult;

/**
 * 审计服务。写入失败必须对调用方可见（不得返回数据却无痕迹）。
 */
public interface AuditService {

    /**
     * 写入审计记录；失败时抛出异常，由上层归一化处理。
     */
    AuditRecord write(AccessRequest request,
                      EvaluationResult result,
                      String inputJson);

    /**
     * 读取审计记录（本地验证/复现用）。
     */
    java.util.List<AuditRecord> findAll();
}
