package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 审计写入失败：放行请求不得返回数据却无痕迹（500 AUDIT_WRITE_FAILED）；
 * 拒绝请求审计失败同样不得静默放行；对外错误不暴露内部细节。
 */
class AuditFailureTests extends AbstractIntegrationTest {

    @AfterEach
    void restore() {
        TestAuditProbe.fail = false;
    }

    @Test
    void allowed_request_fails_closed_when_audit_write_fails() throws Exception {
        TestAuditProbe.fail = true;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "Alice");
        try {
            var result = access(payload(data, "caller-analytics", "analytics", null));
            assertEquals(500, result.getResponse().getStatus());
            var node = json(result);
            assertEquals("AUDIT_WRITE_FAILED", node.path("code").asText());
            String raw = result.getResponse().getContentAsString();
            assertFalse(raw.contains("simulated audit disk failure"),
                    "internal detail must not leak");
        } finally {
            TestAuditProbe.fail = false;
        }

        // 恢复后立即正常
        var ok = access(payload(data, "caller-analytics", "analytics", null));
        assertEquals(200, ok.getResponse().getStatus());
    }

    @Test
    void denied_request_with_failing_audit_is_not_silently_allowed() throws Exception {
        TestAuditProbe.fail = true;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "Alice");
        try {
            var result = access(payload(data, "caller-unknown", "analytics", null));
            // 即使本来要拒绝，审计失败也不得放行
            assertEquals(500, result.getResponse().getStatus());
            assertEquals("AUDIT_WRITE_FAILED", json(result).path("code").asText());
        } finally {
            TestAuditProbe.fail = false;
        }
    }
}
