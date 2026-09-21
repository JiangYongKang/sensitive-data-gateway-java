package com.github.highcumontoa.sensitivedatagatewayjava.audit;

import com.github.highcumontoa.sensitivedatagatewayjava.config.GatewayProperties;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.AuditRecord;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayErrorCode;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存审计存储，带数据保留上限。
 * <ul>
 *   <li>append 加锁串行化，保证审计顺序与可见性；</li>
 *   <li>达到保留上限时拒绝写入并抛出可区分错误，由上层归一化为 AUDIT_WRITE_FAILED，
 *       绝不静默丢弃或覆盖最旧痕迹；</li>
 *   <li>写入异常向上抛出，绝不静默。</li>
 * </ul>
 */
@Component
public class InMemoryAuditStore implements AuditStore {

    private final List<AuditRecord> records = new CopyOnWriteArrayList<>();
    private final Object appendLock = new Object();
    private final GatewayProperties properties;

    public InMemoryAuditStore(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public void append(AuditRecord record) {
        synchronized (appendLock) {
            if (records.size() >= properties.getAuditRetentionMaxRecords()) {
                throw new GatewayException(GatewayErrorCode.AUDIT_WRITE_FAILED,
                        "audit retention limit reached ("
                                + properties.getAuditRetentionMaxRecords()
                                + "); refusing to overwrite history");
            }
            records.add(record);
        }
    }

    @Override
    public List<AuditRecord> findAll() {
        synchronized (appendLock) {
            return new ArrayList<>(records);
        }
    }
}
