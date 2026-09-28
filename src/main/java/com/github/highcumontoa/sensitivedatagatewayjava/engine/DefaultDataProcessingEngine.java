package com.github.highcumontoa.sensitivedatagatewayjava.engine;

import com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationDefinition;
import com.github.highcumontoa.sensitivedatagatewayjava.config.GatewayProperties;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayErrorCode;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.AccessPolicy;
import org.springframework.stereotype.Component;

/**
 * 默认单条数据处理引擎：委托给与批量引擎共用的 {@link RecordProcessor}，
 * 沿用单条访问的深度/规模/耗时上限与错误码。
 */
@Component
public class DefaultDataProcessingEngine implements DataProcessingEngine {

    private final RecordProcessor recordProcessor;
    private final GatewayProperties properties;

    public DefaultDataProcessingEngine(RecordProcessor recordProcessor,
                                       GatewayProperties properties) {
        this.recordProcessor = recordProcessor;
        this.properties = properties;
    }

    @Override
    public Result process(AccessRequest request, AccessPolicy policy, ClassificationDefinition classification) {
        long startNanos = System.nanoTime();
        RecordProcessor.Outcome outcome = recordProcessor.process(
                request.payload(), request, policy, classification,
                properties.getMaxDepth(), properties.getMaxPayloadNodes(),
                startNanos, properties.getProcessingTimeoutMs(),
                GatewayErrorCode.SIZE_LIMIT_EXCEEDED, GatewayErrorCode.TIMEOUT_EXCEEDED);
        return new Result(outcome.data(), outcome.fieldResults(), outcome.decisionBasis(), null, true);
    }
}
