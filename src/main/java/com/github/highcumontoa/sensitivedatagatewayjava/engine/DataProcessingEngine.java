package com.github.highcumontoa.sensitivedatagatewayjava.engine;

import com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationDefinition;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.FieldResult;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayErrorCode;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.AccessPolicy;

import java.util.List;

/**
 * 数据处理引擎：基于指定版本快照遍历结构化/嵌套数据，
 * 做识别、判定与转换，强制深度/规模/耗时上限。
 */
public interface DataProcessingEngine {

    Result process(AccessRequest request, AccessPolicy policy, ClassificationDefinition classification);

    record Result(Object data, List<FieldResult> fieldResults, String decisionBasis,
                  GatewayErrorCode denyReason, boolean allowed) {
    }
}
