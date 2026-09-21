package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import com.github.highcumontoa.sensitivedatagatewayjava.audit.AuditStore;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.AuditRecord;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * 测试配置：提供受 {@link TestAuditProbe} 控制的可失败审计存储，不依赖外部服务。
 */
@TestConfiguration
public class TestConfig {

    @Bean
    @Primary
    public AuditStore probeAuditStore() {
        return new AuditStore() {
            private final List<AuditRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();

            @Override
            public synchronized void append(AuditRecord record) throws Exception {
                if (TestAuditProbe.fail) {
                    throw new IllegalStateException("simulated audit disk failure");
                }
                records.add(record);
            }

            @Override
            public List<AuditRecord> findAll() {
                return List.copyOf(records);
            }
        };
    }
}
