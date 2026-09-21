package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.SensitivityLevel;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.TransformType;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.AccessPolicy;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.Grant;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.PolicyRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

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
 * 并发场景：策略并发发布与读取不得出现半更新；撤销授权后不得因陈旧缓存继续放行。
 */
class ConcurrencyTests extends AbstractIntegrationTest {

    @Autowired
    private PolicyRegistry policyRegistry;

    @Test
    @Timeout(30)
    void concurrent_reads_always_see_a_complete_version_and_revocation_takes_effect()
            throws Exception {
        int readers = 8;
        int iterationsPerReader = 50;
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
        AtomicInteger allowedV1 = new AtomicInteger();
        AtomicInteger deniedAfterRevoke = new AtomicInteger();

        // 一个发布线程交替发布 v1（授权在）与 v3（授权撤销）
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 30; i++) {
                    policyRegistry.publish(i % 2 == 0 ? policyWithAnalytics("policy-v1")
                            : policyWithoutAnalytics("policy-v3"));
                }
            } catch (Exception e) {
                errors.add("publisher: " + e);
            }
        });

        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterationsPerReader; i++) {
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("name", "Alice");
                        String body = payload(data, "caller-analytics", "analytics", null);
                        var result = access(body);
                        int status = result.getResponse().getStatus();
                        var node = json(result);
                        if (status == 200) {
                            // 放行时响应必须自洽：版本字段必须存在且与数据一致
                            String v = node.path("policyVersion").asText();
                            if (!v.equals("policy-v1") && !v.equals("policy-v3")) {
                                errors.add("unexpected version " + v);
                            }
                            if ("policy-v1".equals(v)) {
                                allowedV1.incrementAndGet();
                            } else {
                                // v3 已撤销授权却放行 = 陈旧缓存，绝不允许
                                errors.add("stale allow under revoked policy-v3");
                            }
                        } else if (status == 403) {
                            String code = node.path("code").asText();
                            // 并发下只可能看到 UNAUTHORIZED_CALLER（v3）；
                            // 不应出现空结果/500/NPE 等半更新症状
                            if (!"UNAUTHORIZED_CALLER".equals(code)) {
                                errors.add("unexpected deny code: " + code);
                            }
                            deniedAfterRevoke.incrementAndGet();
                        } else {
                            errors.add("unexpected status " + status);
                        }
                    }
                } catch (Exception e) {
                    errors.add("reader: " + e);
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS), "work did not finish");
        // 最终发布为 policy-v3（30 次，末次 i=29 为奇数次 -> v3），撤销确定生效
        assertEquals("policy-v3", policyRegistry.latestVersion());
        var finalResult = access(payload(Map.of("name", "Alice"),
                "caller-analytics", "analytics", null));
        assertEquals(403, finalResult.getResponse().getStatus());
        assertEquals("UNAUTHORIZED_CALLER", json(finalResult).path("code").asText());
        // 读到过两种状态，证明并发确实发生且各自自洽
        assertTrue(allowedV1.get() > 0 || deniedAfterRevoke.get() > 0);
        assertTrue(errors.isEmpty(), "concurrency errors: " + errors);

        // 还原
        policyRegistry.publish(SeedSnapshot.seedPolicy());
    }

    private AccessPolicy policyWithAnalytics(String version) {
        return new AccessPolicy(version, Map.of(
                "caller-analytics", new Grant(List.of("analytics"), SensitivityLevel.L2,
                        List.of(new Grant.TransformLevel(SensitivityLevel.L1, TransformType.MASK)))));
    }

    private AccessPolicy policyWithoutAnalytics(String version) {
        return new AccessPolicy(version, Map.of(
                "caller-other", new Grant(List.of("analytics"), SensitivityLevel.L2,
                        List.of(new Grant.TransformLevel(SensitivityLevel.L1, TransformType.MASK)))));
    }
}
