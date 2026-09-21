package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.sensitivedatagatewayjava.audit.InMemoryAuditService;
import com.github.highcumontoa.sensitivedatagatewayjava.error.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.limit.GatewayLimits;
import com.github.highcumontoa.sensitivedatagatewayjava.model.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode;
import com.github.highcumontoa.sensitivedatagatewayjava.model.EvaluationResult;
import com.github.highcumontoa.sensitivedatagatewayjava.model.PolicyVersionResolution;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuditRetentionTest {

    private InMemoryAuditService serviceWithRetention(int count) {
        GatewayLimits limits = new GatewayLimits();
        limits.setAuditRetentionCount(count);
        return new InMemoryAuditService(limits);
    }

    private AccessRequest request() {
        return new AccessRequest("c", "p", null, Map.of("k", "v"));
    }

    private EvaluationResult result() {
        return new EvaluationResult(DecisionCode.ALLOW, "ok",
                new PolicyVersionResolution(null, "v1", "v1", false), java.util.List.of());
    }

    @Test
    void retentionEvictsOldest() {
        InMemoryAuditService audit = serviceWithRetention(3);
        for (int i = 0; i < 10; i++) {
            audit.write(request(), result(), "{\"n\":" + i + "}");
        }
        assertEquals(3, audit.findAll().size());
        // 最旧的被淘汰，保留最后三条
        assertTrue(audit.findAll().get(0).inputJson().contains("\"n\":7"));
        assertTrue(audit.findAll().get(2).inputJson().contains("\"n\":9"));
    }

    @Test
    void armedFailureThrowsOnceAndThenRecovers() {
        InMemoryAuditService audit = serviceWithRetention(10);
        audit.armNextWriteFailure();
        GatewayException ex = assertThrows(GatewayException.class,
                () -> audit.write(request(), result(), "{}"));
        assertEquals(DecisionCode.AUDIT_WRITE_FAILED, ex.getCode());
        assertEquals(0, audit.findAll().size(), "失败不得产生记录");

        // 仅下一次失败，之后恢复（不持续阻断）
        assertNotNull(audit.write(request(), result(), "{}"));
        assertEquals(1, audit.findAll().size());
    }
}
