package com.github.highcumontoa.sensitivedatagatewayjava.service;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.BatchAccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.BatchProcessedData;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.ProcessedData;

/**
 * 网关门面：编排 版本解析 -> 判定处理 -> 审计；失败闭合（fail-closed）。
 */
public interface SensitiveDataGateway {

    ProcessedData access(AccessRequest request);

    /**
     * 批量访问：整批共用同一调用方/用途/版本，全有或全无；
     * 任一记录不通过则整批拒绝并定位到记录序号与字段位置。
     */
    BatchProcessedData accessBatch(BatchAccessRequest request);
}
