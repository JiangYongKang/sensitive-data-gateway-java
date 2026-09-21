package com.github.highcumontoa.sensitivedatagatewayjava.limit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可配置的请求规模/深度/耗时上限与审计保留条数。
 */
@ConfigurationProperties(prefix = "gateway.limits")
public class GatewayLimits {

    private int maxPayloadBytes = 65536;
    private int maxFieldCount = 500;
    private int maxDepth = 8;
    private long processTimeoutMillis = 2000;
    private int auditRetentionCount = 1000;

    public int getMaxPayloadBytes() {
        return maxPayloadBytes;
    }

    public void setMaxPayloadBytes(int maxPayloadBytes) {
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public int getMaxFieldCount() {
        return maxFieldCount;
    }

    public void setMaxFieldCount(int maxFieldCount) {
        this.maxFieldCount = maxFieldCount;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public long getProcessTimeoutMillis() {
        return processTimeoutMillis;
    }

    public void setProcessTimeoutMillis(long processTimeoutMillis) {
        this.processTimeoutMillis = processTimeoutMillis;
    }

    public int getAuditRetentionCount() {
        return auditRetentionCount;
    }

    public void setAuditRetentionCount(int auditRetentionCount) {
        this.auditRetentionCount = auditRetentionCount;
    }
}
