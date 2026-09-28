package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import com.github.highcumontoa.sensitivedatagatewayjava.audit.AuditStore;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.AccessPolicy;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.Grant;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.PolicyRegistry;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.SensitivityLevel;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.TransformType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本管理：新请求按最新版本生效；显式回退旧版本被明确拒绝；
 * 历史审计记录保留发生时策略版本，可解释、不被静默混用。
 */
class PolicyVersionTests extends AbstractIntegrationTest {

    @Autowired
    private PolicyRegistry policyRegistry;

    @Autowired
    private AuditStore auditStore;

    @BeforeEach
    void resetToV1() {
        policyRegistry.publish(SeedSnapshot.seedPolicy());
    }

    private Map<String, Object> data() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", "Alice");
        return d;
    }

    @Test
    void explicit_older_version_is_rejected() throws Exception {
        String body = payload(data(), "caller-analytics", "analytics", "policy-v1");
        // 初始状态最新即 v1，先确认等价版本可用
        var ok = access(body);
        assertEquals(200, ok.getResponse().getStatus());

        publishV2();
        try {
            var rejected = access(body);
            assertEquals(403, rejected.getResponse().getStatus());
            var node = json(rejected);
            assertEquals("POLICY_VERSION_FALLBACK_REJECTED", node.path("code").asText());
            assertTrue(node.path("message").asText().contains("policy-v2"));

            // 不带版本 -> 按最新 v2 生效（v2 中仅 support 保留授权）
            var latestBody = payload(data(), "caller-support", "customer-support", null);
            var byLatest = access(latestBody);
            assertEquals(200, byLatest.getResponse().getStatus());
            assertEquals("policy-v2", json(byLatest).path("policyVersion").asText());

            // 审计记录中回退拒绝也留有痕迹，且标记发生时最新版本
            var records = auditStore.findAll();
            boolean fallbackAudit = records.stream().anyMatch(r ->
                    "POLICY_VERSION_FALLBACK_REJECTED".equals(
                            r.denyReason() == null ? null : r.denyReason().name())
                            // 记录请求指定的旧版本，便于解释“谁试图回退到哪个版本”
                            && "policy-v1".equals(r.policyVersion()));
            assertTrue(fallbackAudit);
            // 早先成功的记录仍记录 v1，可按历史版本复盘
            boolean historicalV1 = records.stream().anyMatch(r ->
                    r.allowed() && "policy-v1".equals(r.policyVersion()));
            assertTrue(historicalV1);
        } finally {
            // 还原最新版本，避免污染其他测试（注册表是单例）
            policyRegistry.publish(SeedSnapshot.seedPolicy());
        }
    }

    @Test
    void revoked_grant_takes_effect_without_stale_cache() throws Exception {
        publishV2();
        try {
            // v2 撤销了 analytics 授权：同一调用方立即被拒
            var result = access(payload(data(), "caller-analytics", "analytics", null));
            assertEquals(403, result.getResponse().getStatus());
            assertEquals("UNAUTHORIZED_CALLER", json(result).path("code").asText());
        } finally {
            policyRegistry.publish(SeedSnapshot.seedPolicy());
        }
    }

    @Test
    void unknown_version_is_not_found() throws Exception {
        var result = access(payload(data(), "caller-analytics", "analytics", "policy-999"));
        // 从未发布过的版本：404 POLICY_NOT_FOUND，与“已发布但非当前”的 403 回退拒绝可区分
        assertEquals(404, result.getResponse().getStatus());
        assertEquals("POLICY_NOT_FOUND", json(result).path("code").asText());
        // 仍然留下审计痕迹
        boolean notFoundAudit = auditStore.findAll().stream().anyMatch(r ->
                "POLICY_NOT_FOUND".equals(r.denyReason() == null ? null : r.denyReason().name())
                        && "policy-999".equals(r.policyVersion()));
        assertTrue(notFoundAudit);
    }

    private void publishV2() {
        Map<String, Grant> grants = new LinkedHashMap<>();
        // analytics 授权被撤销；support 仍在
        grants.put("caller-support", new Grant(
                List.of("customer-support"),
                SensitivityLevel.L3,
                List.of(new Grant.TransformLevel(SensitivityLevel.L1, TransformType.NONE))));
        policyRegistry.publish(new AccessPolicy("policy-v2", grants));
    }
}
