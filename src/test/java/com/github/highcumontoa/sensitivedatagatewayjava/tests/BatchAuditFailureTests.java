package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 批量审计 fail-closed：放行批次审计写失败不得返回数据；拒绝批次审计失败也不得静默放行。
 */
class BatchAuditFailureTests extends AbstractIntegrationTest {

    private Map<String, Object> record(String name) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", name);
        return d;
    }

    @AfterEach
    void restore() {
        TestAuditProbe.fail = false;
    }

    @Test
    void allowed_batch_fails_closed_when_audit_write_fails() throws Exception {
        TestAuditProbe.fail = true;
        try {
            var result = accessBatch(batchPayload(
                    List.of(record("Alice"), record("Bob")),
                    "caller-analytics", "analytics", null));
            assertEquals(500, result.getResponse().getStatus());
            assertEquals("AUDIT_WRITE_FAILED", json(result).path("code").asText());
            String raw = result.getResponse().getContentAsString();
            assertFalse(raw.contains("simulated audit disk failure"),
                    "internal detail must not leak");
        } finally {
            TestAuditProbe.fail = false;
        }
        var ok = accessBatch(batchPayload(List.of(record("Alice")),
                "caller-analytics", "analytics", null));
        assertEquals(200, ok.getResponse().getStatus());
    }

    @Test
    void denied_batch_with_failing_audit_is_not_silently_allowed() throws Exception {
        TestAuditProbe.fail = true;
        try {
            var result = accessBatch(batchPayload(
                    List.of(record("Alice")), "caller-unknown", "analytics", null));
            assertEquals(500, result.getResponse().getStatus());
            assertEquals("AUDIT_WRITE_FAILED", json(result).path("code").asText());
        } finally {
            TestAuditProbe.fail = false;
        }
    }
}
