package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import com.github.highcumontoa.sensitivedatagatewayjava.audit.AuditStore;
import com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationDefinition;
import com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationRegistry;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.SensitivityLevel;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.AccessPolicy;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.Grant;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.PolicyRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量版本语义：
 * <ul>
 *   <li>从未发布的版本 -> POLICY_NOT_FOUND（404）；已发布但非当前 -> FALLBACK_REJECTED（403），
 *       二者可区分且都留审计，绝不拿当前版本代替；分级版本同理；</li>
 *   <li>整批只按开始时固定的快照处理，期间并发发布不影响批内结果与版本字段。</li>
 * </ul>
 */
class BatchVersionTests extends AbstractIntegrationTest {

    @Autowired
    private PolicyRegistry policyRegistry;

    @Autowired
    private ClassificationRegistry classificationRegistry;

    @Autowired
    private AuditStore auditStore;

    @BeforeEach
    void reset() {
        policyRegistry.publish(SeedSnapshot.seedPolicy());
        classificationRegistry.publish(SeedSnapshot.seedClassification());
    }

    private List<Object> batch() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", "Alice");
        r.put("email", "alice@example.com");
        return List.of(r);
    }

    @Test
    void never_published_policy_version_is_not_found() throws Exception {
        var result = accessBatch(batchPayload(batch(), "caller-analytics",
                "analytics", "policy-404", null));
        assertEquals(404, result.getResponse().getStatus());
        JsonNode node = json(result);
        assertEquals("POLICY_NOT_FOUND", node.path("code").asText());
        assertEquals("VERSION", node.path("category").asText());
        boolean audited = auditStore.findAll().stream().anyMatch(a ->
                "POLICY_NOT_FOUND".equals(a.denyReason() == null ? null : a.denyReason().name())
                        && a.batch() && "policy-404".equals(a.policyVersion()));
        assertTrue(audited, "never-published version rejection must leave a batch audit");
    }

    @Test
    void published_but_non_current_policy_version_is_distinct_rejection() throws Exception {
        // 发布 v2（v1 仍是已发布历史版本）
        Map<String, Grant> grants = new LinkedHashMap<>();
        grants.put("caller-analytics", new Grant(List.of("analytics"),
                SensitivityLevel.L2, List.of(new Grant.TransformLevel(
                SensitivityLevel.L1, com.github.highcumontoa.sensitivedatagatewayjava.domain.TransformType.MASK))));
        policyRegistry.publish(new AccessPolicy("policy-v2", grants));
        try {
            var result = accessBatch(batchPayload(batch(), "caller-analytics",
                    "analytics", "policy-v1", null));
            assertEquals(403, result.getResponse().getStatus());
            JsonNode node = json(result);
            assertEquals("POLICY_VERSION_FALLBACK_REJECTED", node.path("code").asText());
            assertEquals("VERSION", node.path("category").asText());
            boolean audited = auditStore.findAll().stream().anyMatch(a ->
                    "POLICY_VERSION_FALLBACK_REJECTED".equals(
                            a.denyReason() == null ? null : a.denyReason().name())
                            && a.batch() && "policy-v1".equals(a.policyVersion()));
            assertTrue(audited, "published-but-non-current rejection must leave a batch audit");

            // 不指定版本 -> 按当前 v2 正常放行
            var ok = accessBatch(batchPayload(batch(), "caller-analytics",
                    "analytics", null, null));
            assertEquals(200, ok.getResponse().getStatus());
            assertEquals("policy-v2", json(ok).path("policyVersion").asText());
        } finally {
            policyRegistry.publish(SeedSnapshot.seedPolicy());
        }
    }

    @Test
    void never_published_classification_version_is_distinct_from_non_current() throws Exception {
        // 先造一个已发布但非当前的分级版本 classification-v2
        ClassificationDefinition v1 = SeedSnapshot.seedClassification();
        ClassificationDefinition v2 = new ClassificationDefinition("classification-v2",
                v1.fieldLevels(), v1.unclassified(), v1.required());
        classificationRegistry.publish(v2);
        try {
            // 从未发布 -> CLASSIFICATION_VERSION_NOT_FOUND（404）
            var missing = accessBatch(batchPayload(batch(), "caller-analytics",
                    "analytics", null, "classification-404"));
            assertEquals(404, missing.getResponse().getStatus());
            assertEquals("CLASSIFICATION_VERSION_NOT_FOUND",
                    json(missing).path("code").asText());

            // 已发布但非当前 -> CLASSIFICATION_VERSION_FALLBACK_REJECTED（403）
            var old = accessBatch(batchPayload(batch(), "caller-analytics",
                    "analytics", null, "classification-v1"));
            assertEquals(403, old.getResponse().getStatus());
            assertEquals("CLASSIFICATION_VERSION_FALLBACK_REJECTED",
                    json(old).path("code").asText());
        } finally {
            classificationRegistry.publish(SeedSnapshot.seedClassification());
        }
    }
}
