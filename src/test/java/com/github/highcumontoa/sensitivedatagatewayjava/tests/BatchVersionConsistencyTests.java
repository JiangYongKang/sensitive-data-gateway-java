package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import com.github.highcumontoa.sensitivedatagatewayjava.audit.AuditStore;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.AuditRecord;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.SensitivityLevel;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.TransformType;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.AccessPolicy;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.Grant;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.PolicyRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量版本一致性：整批使用同一不可变快照，并发发布不混用新旧规则；
 * 已发布非当前版本与从未发布版本给出可区分拒绝；撤销授权不依赖陈旧缓存。
 */
class BatchVersionConsistencyTests extends AbstractIntegrationTest {

    @Autowired
    private PolicyRegistry policyRegistry;

    @Autowired
    private AuditStore auditStore;

    private Map<String, Object> record() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", "Alice");
        d.put("email", "alice@example.com");
        return d;
    }

    private List<Object> records(int n) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(record());
        }
        return list;
    }

    @Test
    void concurrent_publish_never_mixes_versions_within_a_batch() throws Exception {
        // 开始前固定为 v1，并放行一个批次，确保存在“v1 成功批次”审计
        policyRegistry.publish(withAnalytics("policy-v1"));
        var warmup = accessBatch(batchPayload(records(5), "caller-analytics", "analytics", null));
        assertEquals(200, warmup.getResponse().getStatus());

        int readers = 8;
        int iterations = 40;
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        CountDownLatch start = new CountDownLatch(1);
        // 读者先在栅栏等待；发布线程稍延后再开始交替，保证在途批次能观察到 v1 快照
        CountDownLatch readersReady = new CountDownLatch(readers);
        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
        AtomicInteger v1Batches = new AtomicInteger();
        AtomicInteger v3Denied = new AtomicInteger();

        pool.submit(() -> {
            try {
                start.await();
                Thread.sleep(20);
                for (int i = 0; i < 60; i++) {
                    policyRegistry.publish(i % 2 == 0 ? withAnalytics("policy-v1")
                            : withoutAnalytics("policy-v3"));
                }
            } catch (Exception e) {
                errors.add("publisher: " + e);
            }
        });

        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                try {
                    readersReady.countDown();
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        var result = accessBatch(
                                batchPayload(records(5), "caller-analytics", "analytics", null));
                        int status = result.getResponse().getStatus();
                        var node = json(result);
                        if (status == 200) {
                            String v = node.path("policyVersion").asText();
                            // 整批所有记录必须按同一版本处理；在 v3 下放行=陈旧缓存，绝不允许
                            if ("policy-v1".equals(v)) {
                                if (node.path("data").size() != 5) {
                                    errors.add("partial batch size " + node.path("data").size());
                                }
                                v1Batches.incrementAndGet();
                            } else {
                                errors.add("stale allow under " + v);
                            }
                        } else if (status == 403) {
                            if (!"UNAUTHORIZED_CALLER".equals(node.path("code").asText())) {
                                errors.add("unexpected deny: " + node.path("code").asText());
                            }
                            v3Denied.incrementAndGet();
                        } else {
                            errors.add("unexpected status " + status);
                        }
                    }
                } catch (Exception e) {
                    errors.add("reader: " + e);
                }
            });
        }

        readersReady.await(5, TimeUnit.SECONDS);
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS), "work did not finish");
        assertTrue(errors.isEmpty(), "consistency errors: " + errors);
        // 两种状态都应被观察到（v1 快照放行 + v3 撤销拒绝），证明并发确实发生且各自自洽
        assertTrue(v1Batches.get() > 0, "expected at least one v1 batch, got " + v1Batches.get());
        assertTrue(v3Denied.get() > 0, "expected at least one v3 denial, got " + v3Denied.get());

        // 审计记录：成功的批次必须固化与响应一致的版本，且记录数为整批大小
        boolean batchAuditSelfConsistent = auditStore.findAll().stream()
                .filter(AuditRecord::batch)
                .filter(a -> "policy-v1".equals(a.policyVersion()))
                .filter(a -> Integer.valueOf(5).equals(a.recordCount()))
                .anyMatch(AuditRecord::allowed);
        assertTrue(batchAuditSelfConsistent, "batch audit must pin version and record count");

        policyRegistry.publish(SeedSnapshot.seedPolicy());
    }

    @Test
    void published_but_non_current_version_is_rejected_distinctly_for_batch() throws Exception {
        policyRegistry.publish(withoutAnalytics("policy-v2"));
        try {
            var result = accessBatch(
                    batchPayload(records(2), "caller-support", "customer-support", "policy-v1"));
            assertEquals(403, result.getResponse().getStatus());
            var node = json(result);
            assertEquals("POLICY_VERSION_FALLBACK_REJECTED", node.path("code").asText());
            assertTrue(node.path("message").asText().contains("policy-v2"));
            // 不拿当前版本代替，且留下批次审计
            boolean audited = auditStore.findAll().stream().anyMatch(a ->
                    a.batch() && "policy-v1".equals(a.policyVersion())
                            && "POLICY_VERSION_FALLBACK_REJECTED"
                            .equals(a.denyReason() == null ? null : a.denyReason().name()));
            assertTrue(audited);
        } finally {
            policyRegistry.publish(SeedSnapshot.seedPolicy());
        }
    }

    @Test
    void never_published_version_is_not_found_for_batch() throws Exception {
        var result = accessBatch(
                batchPayload(records(2), "caller-analytics", "analytics", "policy-404"));
        assertEquals(404, result.getResponse().getStatus());
        var node = json(result);
        assertEquals("POLICY_NOT_FOUND", node.path("code").asText());
        boolean audited = auditStore.findAll().stream().anyMatch(a ->
                a.batch() && "policy-404".equals(a.policyVersion())
                        && "POLICY_NOT_FOUND"
                        .equals(a.denyReason() == null ? null : a.denyReason().name()));
        assertTrue(audited);
    }

    @Test
    void revoked_grant_takes_effect_for_batch_without_stale_cache() throws Exception {
        // 先在 v1 下放行一次，再发布撤销版本；下一批必须立即被拒
        var ok = accessBatch(batchPayload(records(2), "caller-analytics", "analytics", null));
        assertEquals(200, ok.getResponse().getStatus());
        policyRegistry.publish(withoutAnalytics("policy-v2"));
        try {
            var denied = accessBatch(
                    batchPayload(records(2), "caller-analytics", "analytics", null));
            assertEquals(403, denied.getResponse().getStatus());
            assertEquals("UNAUTHORIZED_CALLER", json(denied).path("code").asText());
        } finally {
            policyRegistry.publish(SeedSnapshot.seedPolicy());
        }
    }

    private AccessPolicy withAnalytics(String version) {
        return new AccessPolicy(version, Map.of(
                "caller-analytics", new Grant(List.of("analytics"), SensitivityLevel.L2,
                        List.of(new Grant.TransformLevel(SensitivityLevel.L1, TransformType.MASK),
                                new Grant.TransformLevel(SensitivityLevel.L2, TransformType.TOKENIZE))),
                "caller-support", new Grant(List.of("customer-support"), SensitivityLevel.L3,
                        List.of(new Grant.TransformLevel(SensitivityLevel.L1, TransformType.NONE)))));
    }

    private AccessPolicy withoutAnalytics(String version) {
        return new AccessPolicy(version, Map.of(
                "caller-support", new Grant(List.of("customer-support"), SensitivityLevel.L3,
                        List.of(new Grant.TransformLevel(SensitivityLevel.L1, TransformType.NONE)))));
    }
}
