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
}
