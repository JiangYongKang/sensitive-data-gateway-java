package com.github.highcumontoa.sensitivedatagatewayjava.service;

import com.github.highcumontoa.sensitivedatagatewayjava.model.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.model.AccessResponse;
import com.github.highcumontoa.sensitivedatagatewayjava.model.DenyResponse;

/**
 * 网关编排：解析版本 → 扫描 → 判定 → 审计 →（仅成功时）转换输出。
 */
public interface GatewayService {

    /**
     * 处理一次访问。任何拒绝都返回结构化原因；审计失败时抛异常且不得返回数据。
     */
    AccessResponse access(AccessRequest request, String rawInputJson);

    /**
     * 将拒绝结果（已审计）包装为对外响应。
     */
    DenyResponse deny(String auditId, String policyVersion,
                      com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode code,
                      String reason, boolean audited);
}
