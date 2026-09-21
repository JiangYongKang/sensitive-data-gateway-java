package com.github.highcumontoa.sensitivedatagatewayjava.service;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.ProcessedData;

/**
 * 网关门面：编排 版本解析 -> 判定处理 -> 审计；失败闭合（fail-closed）。
 */
public interface SensitiveDataGateway {

    ProcessedData access(AccessRequest request);
}
