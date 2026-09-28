package com.github.highcumontoa.sensitivedatagatewayjava.service;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.BatchAccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.BatchProcessedData;

/**
 * 批量网关门面：整批共用一个调用方、用途与版本快照，全有或全无。
 */
public interface BatchSensitiveDataGateway {

    BatchProcessedData accessBatch(BatchAccessRequest request);
}
