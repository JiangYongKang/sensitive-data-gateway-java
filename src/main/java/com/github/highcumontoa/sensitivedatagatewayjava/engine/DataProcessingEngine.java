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
 * <p>单条 {@link #process} 保持原有语义；批量场景先 {@link #preflightBatch}
 * 在任何转换之前做整批上限与契约预检，再逐条 {@link #processBatchRecord}，
 * 任意一条失败即抛出带记录序号/字段路径的异常，不产生半成品。
 */
public interface DataProcessingEngine {

    Result process(AccessRequest request, AccessPolicy policy, ClassificationDefinition classification);

    /**
     * 整批预检（转换之前执行）：每条记录必须是对象；逐条强制单条深度上限；
     * 整批合计节点数强制总规模上限；逐记录检查必备敏感字段；期间检查耗时上限。
     * 超限/契约异常带记录序号抛出。不修改任何输入。
     *
     * @param maxRecordDepth 单条记录的嵌套深度上限
     * @param maxTotalNodes  整批合计节点上限
     * @param startNanos     整批起始时间（与处理阶段共享同一把耗时尺子）
     */
    void preflightBatch(List<Object> records, AccessRequest request,
                        AccessPolicy policy, ClassificationDefinition classification,
                        int maxRecordDepth, long maxTotalNodes, long startNanos);

    /**
     * 处理批量中的一条记录（基于整批共享的版本快照与起始时间）。
     *
     * @param recordIndex 该记录在整批中的序号（从 0 开始）
     */
    RecordResult processBatchRecord(List<Object> records, int recordIndex, AccessRequest request,
                                    AccessPolicy policy, ClassificationDefinition classification,
                                    long startNanos);

    record Result(Object data, List<FieldResult> fieldResults, String decisionBasis,
                  GatewayErrorCode denyReason, boolean allowed) {
    }

    /**
     * 批量中单条记录的处理产出。
     */
    record RecordResult(Object data, List<FieldResult> fieldResults, String decisionBasis) {
    }
}
