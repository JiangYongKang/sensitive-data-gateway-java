package com.github.highcumontoa.sensitivedatagatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可配置上限：请求规模、嵌套深度、处理耗时（毫秒）。
 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private int maxPayloadNodes = 1000;
    private int maxDepth = 10;
    private long processingTimeoutMs = 2000;
    /**
     * 批量请求允许的最大记录条数；超限在处理前以 BATCH_SIZE_LIMIT_EXCEEDED 拒绝整批。
     */
    private int maxBatchRecords = 100;
    /**
     * 批量请求中单条记录允许的最大节点数；超限在处理前以 RECORD_SIZE_LIMIT_EXCEEDED
     * 拒绝整批（沿用 {@link #maxDepth} 作为单条深度上限）。
     */
    private int maxBatchRecordNodes = 1000;
    /**
     * 整批处理耗时上限（毫秒）；超限以 BATCH_TIMEOUT_EXCEEDED 拒绝整批。
     */
    private long batchTimeoutMs = 10000;
    /**
     * 审计数据保留上限：内存中最多保留多少条审计记录；
     * 达到上限后写入被拒绝（fail-closed），不会静默丢弃历史痕迹。
     */
    private int auditRetentionMaxRecords = 10000;

    public int getAuditRetentionMaxRecords() {
        return auditRetentionMaxRecords;
    }

    public void setAuditRetentionMaxRecords(int auditRetentionMaxRecords) {
        this.auditRetentionMaxRecords = auditRetentionMaxRecords;
    }

    public int getMaxPayloadNodes() {
        return maxPayloadNodes;
    }

    public void setMaxPayloadNodes(int maxPayloadNodes) {
        this.maxPayloadNodes = maxPayloadNodes;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public long getProcessingTimeoutMs() {
        return processingTimeoutMs;
    }

    public void setProcessingTimeoutMs(long processingTimeoutMs) {
        this.processingTimeoutMs = processingTimeoutMs;
    }

    public int getMaxBatchRecords() {
        return maxBatchRecords;
    }

    public void setMaxBatchRecords(int maxBatchRecords) {
        this.maxBatchRecords = maxBatchRecords;
    }

    public int getMaxBatchRecordNodes() {
        return maxBatchRecordNodes;
    }

    public void setMaxBatchRecordNodes(int maxBatchRecordNodes) {
        this.maxBatchRecordNodes = maxBatchRecordNodes;
    }

    public long getBatchTimeoutMs() {
        return batchTimeoutMs;
    }

    public void setBatchTimeoutMs(long batchTimeoutMs) {
        this.batchTimeoutMs = batchTimeoutMs;
    }
}
