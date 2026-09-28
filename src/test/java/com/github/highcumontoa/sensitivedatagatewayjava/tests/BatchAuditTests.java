package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import com.github.highcumontoa.sensitivedatagatewayjava.audit.AuditStore;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.AuditRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量审计：成功时一条批次记录可按 records[i]. 路径还原每条记录的字段说明；
 * 整批失败时同样留一条批次审计并携带失败记录序号与字段位置。
 */
class BatchAuditTests extends AbstractIntegrationTest {

    @Autowired
    private AuditStore auditStore;

    private Map<String, Object> rec(String name, String email) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("email", email);
        return m;
    }

    @Test
    void successful_batch_writes_one_audit_reconstructable_per_record() throws Exception {
        List<Object> records = List.of(
                rec("Alice", "alice@example.com"),
                rec("Bob", "bob@example.com"));
        var result = accessBatch(batchPayload(records, "caller-analytics",
                "analytics", null, null));
        assertEquals(200, result.getResponse().getStatus());
        String auditId = json(result).path("auditId").asText();

        AuditRecord audit = auditStore.findAll().stream()
                .filter(a -> a.auditId().equals(auditId)).findFirst().orElseThrow();
        assertTrue(audit.batch());
        assertEquals(2, audit.recordCount());
        assertTrue(audit.allowed());
        assertEquals("policy-v1", audit.policyVersion());
        assertEquals("classification-v1", audit.classificationVersion());

        List<String> paths = audit.fields().stream().map(f -> f.path()).toList();
        // 全局路径可按记录还原
        assertTrue(paths.contains("records[0].name"));
        assertTrue(paths.contains("records[0].email"));
        assertTrue(paths.contains("records[1].name"));
        assertTrue(paths.contains("records[1].email"));
        assertNotNull(audit.requestHash());
    }

    @Test
    void rejected_batch_writes_audit_with_record_index_and_field() throws Exception {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("name", "Bad");
        bad.put("address", "secret street"); // analytics maxLevel=L2, address=L3
        var result = accessBatch(batchPayload(
                List.of(rec("Alice", "a@b.com"), bad),
                "caller-analytics", "analytics", null, null));
        assertEquals(403, result.getResponse().getStatus());

        AuditRecord audit = auditStore.findAll().stream()
                .filter(AuditRecord::batch)
                .filter(a -> !a.allowed())
                .reduce((a, b) -> b).orElseThrow();
        assertEquals("LEVEL_NOT_GRANTED", audit.denyReason().name());
        assertEquals(1, audit.recordIndex());
        assertEquals("address", audit.fieldPath());
        assertEquals(2, audit.recordCount());
    }
}
