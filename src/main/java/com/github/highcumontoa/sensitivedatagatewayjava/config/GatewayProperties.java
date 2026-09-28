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
     * 单批最大记录数；超过则在处理前以 BATCH_SIZE_LIMIT_EXCEEDED 拒绝。
     */
    private int maxBatchRecords = 200;
    /**
     * 整批（所有记录合计）最大节点数；超过则在处理前以 BATCH_SIZE_LIMIT_EXCEEDED 拒绝。
     */
    private long maxBatchTotalNodes = 50000;
    /**
     * 单条记录允许的最大嵌套深度（批量场景按记录逐一强制）。
     */
    private int maxBatchRecordDepth = 10;
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

    public long getMaxBatchTotalNodes() {
        return maxBatchTotalNodes;
    }

    public void setMaxBatchTotalNodes(long maxBatchTotalNodes) {
        this.maxBatchTotalNodes = maxBatchTotalNodes;
    }

    public int getMaxBatchRecordDepth() {
        return maxBatchRecordDepth;
    }

    public void setMaxBatchRecordDepth(int maxBatchRecordDepth) {
        this.maxBatchRecordDepth = maxBatchRecordDepth;
    }
}
