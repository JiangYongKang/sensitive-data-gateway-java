package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.sensitivedatagatewayjava.audit.InMemoryAuditService;
import com.github.highcumontoa.sensitivedatagatewayjava.error.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.model.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.model.AccessResponse;
import com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode;
import com.github.highcumontoa.sensitivedatagatewayjava.model.TransformType;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.PolicyStore;
import com.github.highcumontoa.sensitivedatagatewayjava.service.DefaultGatewayService;
import com.github.highcumontoa.sensitivedatagatewayjava.model.PolicySnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class GatewayIntegrationTest {

    @Autowired
    private DefaultGatewayService service;
    @Autowired
    private PolicyStore store;
    @Autowired
    private InMemoryAuditService auditService;
    @Autowired
    private ObjectMapper objectMapper;

    private Map<String, Object> basePayload() {
        Map<String, Object> user = new HashMap<>();
        user.put("name", "Alice");
        user.put("email", "alice@example.com");
        user.put("phone", "13800000000");
        Map<String, Object> root = new HashMap<>();
        root.put("user", user);
        return root;
    }

    private String raw(Object payload) {
        try {
            return objectMapper.writeValueAsString(new AccessRequest(
                    "svc-analytics", "analytics", null, (Map<String, Object>) payload));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private AccessRequest req(String caller, String purpose, String version,
                              Map<String, Object> payload) {
        return new AccessRequest(caller, purpose, version, payload);
    }

    private DefaultGatewayService.DeniedException denyOf(Runnable r) {
        return assertThrows(DefaultGatewayService.DeniedException.class, r::run);
    }

    @Test
    void allowedAccessStableMaskedTokenizedAndAudited() throws Exception {
        Map<String, Object> p1 = basePayload();
        AccessResponse r1 = service.access(
                req("svc-analytics", "analytics", null, p1), raw(p1));
        assertEquals(store.snapshot().latestVersion(), r1.policyVersion());

        Map<?, ?> user = (Map<?, ?>) r1.data().get("user");
        assertEquals("Alice", user.get("name"), "NONE 原样");
        assertEquals("1*********0", user.get("phone"), "MASK 掩码");
        String token1 = (String) user.get("email");
        assertTrue(token1.startsWith("tok_"), "TOKENIZE 令牌化");
        assertNotEquals("alice@example.com", token1);

        // 不可逆标注
        assertTrue(r1.fieldMarkers().stream().anyMatch(m ->
                m.fieldPath().equals("user.email")
                        && m.transform() == TransformType.TOKENIZE
                        && m.irreversible()));
        assertTrue(r1.fieldMarkers().stream().noneMatch(m ->
                m.transform() == TransformType.MASK && m.irreversible()));

        // 同一原始值同一策略稳定
        Map<String, Object> p2 = basePayload();
        AccessResponse r2 = service.access(
                req("svc-analytics", "analytics", null, p2), raw(p2));
        assertEquals(token1, ((Map<?, ?>) r2.data().get("user")).get("email"));

        // 原始入参未被修改
        assertEquals("alice@example.com",
                ((Map<?, ?>) p1.get("user")).get("email"));

        // 审计记录含版本与判定
        boolean found = auditService.findAll().stream()
                .anyMatch(a -> a.auditId().equals(r1.auditId())
                        && a.decisionCode() == DecisionCode.ALLOW
                        && a.policyVersion().equals(r1.policyVersion())
                        && a.inputJson().contains("alice@example.com"));
        assertTrue(found, "审计必须记录原始输入与策略版本");
    }

    @Test
    void unauthorizedAndRevokedCallerRejectedWithDistinctReason() {
        DefaultGatewayService.DeniedException unknown = denyOf(() ->
                service.access(req("svc-unknown", "analytics", null, basePayload()),
                        raw(basePayload())));
        assertEquals(DecisionCode.UNAUTHORIZED_CALLER, unknown.getCode());
        assertTrue(unknown.isAudited());

        DefaultGatewayService.DeniedException revoked = denyOf(() ->
                service.access(req("svc-legacy", "analytics", null, basePayload()),
                        raw(basePayload())));
        assertEquals(DecisionCode.UNAUTHORIZED_CALLER, revoked.getCode());
        assertTrue(revoked.getMessage().contains("撤销"));
        assertNotEquals(unknown.getMessage(), revoked.getMessage());
    }

    @Test
    void purposeMismatchRejected() {
        // svc-support 没有 analytics 用途
        DefaultGatewayService.DeniedException denied = denyOf(() ->
                service.access(req("svc-support", "analytics", null, basePayload()),
                        raw(basePayload())));
        assertEquals(DecisionCode.PURPOSE_MISMATCH, denied.getCode());
    }

    @Test
    void explicitFallbackToOldVersionDenied() {
        PolicySnapshot snapshot = store.snapshot();
        List<String> versions = snapshot.versions().keySet().stream().sorted().toList();
        String oldest = versions.get(0);
        String latest = snapshot.latestVersion();
        assertNotEquals(oldest, latest, "种子应含多个版本");

        DefaultGatewayService.DeniedException denied = denyOf(() ->
                service.access(req("svc-analytics", "analytics", oldest, basePayload()),
                        raw(basePayload())));
        assertEquals(DecisionCode.POLICY_FALLBACK_DENIED, denied.getCode());
        assertTrue(denied.getMessage().contains(oldest));
        // 审计仍按发生时记录（记录被请求的版本与最新版本）
        assertTrue(auditService.findAll().stream().anyMatch(a ->
                a.decisionCode() == DecisionCode.POLICY_FALLBACK_DENIED
                        && oldest.equals(a.requestedVersion())
                        && latest.equals(a.latestVersion())));
    }

    @Test
    void missingRequiredFieldRejected() {
        Map<String, Object> user = new HashMap<>();
        user.put("name", "Alice"); // 缺 email（必填）
        Map<String, Object> payload = new HashMap<>();
        payload.put("user", user);
        DefaultGatewayService.DeniedException denied = denyOf(() ->
                service.access(req("svc-analytics", "analytics", null, payload),
                        raw(payload)));
        assertEquals(DecisionCode.FIELD_MISSING, denied.getCode());
        assertTrue(denied.getMessage().contains("email"));
    }

    @Test
    void unclassifiedSensitiveFieldRejected() {
        Map<String, Object> payload = basePayload();
        ((Map<String, Object>) payload.get("user")).put("bankCard", "6222000000000000");
        DefaultGatewayService.DeniedException denied = denyOf(() ->
                service.access(req("svc-analytics", "analytics", null, payload),
                        raw(payload)));
        assertEquals(DecisionCode.CLASSIFICATION_UNDEFINED, denied.getCode());
    }

    @Test
    void typeMismatchInNestedFieldRejected() {
        Map<String, Object> payload = basePayload();
        ((Map<String, Object>) payload.get("user")).put("phone", 13800000000L);
        DefaultGatewayService.DeniedException denied = denyOf(() ->
                service.access(req("svc-analytics", "analytics", null, payload),
                        raw(payload)));
        assertEquals(DecisionCode.MALFORMED_DATA, denied.getCode());
    }

    @Test
    void auditFailureNeverReturnsData() {
        long before = auditService.findAll().size();
        auditService.armNextWriteFailure();
        GatewayException ex = assertThrows(GatewayException.class, () ->
                service.access(req("svc-analytics", "analytics", null, basePayload()),
                        raw(basePayload())));
        assertEquals(DecisionCode.AUDIT_WRITE_FAILED, ex.getCode());
        long after = auditService.findAll().size();
        assertEquals(before, after, "审计失败不得写入成功记录，也不得返回数据");
    }

    @Test
    void payloadTooLargeRejected() {
        Map<String, Object> hugeUser = new HashMap<>();
        hugeUser.put("name", "x".repeat(70000));
        hugeUser.put("email", "a@example.com");
        Map<String, Object> payload = new HashMap<>();
        payload.put("user", hugeUser);
        GatewayException ex = assertThrows(GatewayException.class,
                () -> service.access(req("svc-analytics", "analytics", null, payload),
                        raw(payload)));
        assertEquals(DecisionCode.LIMIT_PAYLOAD_TOO_LARGE, ex.getCode());
    }
}
