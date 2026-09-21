package com.github.highcumontoa.sensitivedatagatewayjava.audit;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.AuditRecord;

import java.util.List;

/**
 * 审计存储：写入失败必须向上抛出，禁止静默。
 */
public interface AuditStore {

    void append(AuditRecord record) throws Exception;

    List<AuditRecord> findAll();
}
