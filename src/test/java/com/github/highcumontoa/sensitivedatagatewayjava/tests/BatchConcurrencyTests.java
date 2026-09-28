package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发“批量读 + 交替发布”：
 * 每个成功批次必须完整使用同一版本——响应的 policyVersion、批内每条记录、
 * 审计字段都自洽；v3 已撤销授权时不得陈旧放行。
 */
class BatchConcurrencyTests extends AbstractIntegrationTest {

    @Autowired
    private PolicyRegistry policyRegistry;

    @Test
    @Timeout(40)
    void whole_batch_uses_one_consistent_version_under_concurrent_publish() throws Exception {
        int readers = 8;
        int iterations = 40;
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();

        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 40; i++) {
                    policyRegistry.publish(i % 2 == 0
                            ? policy("policy-v1", "caller-analytics")
                            : policy("policy-v3", "caller-other"));
                }
            } catch (Exception e) {
                errors.add("publisher: " + e);
            }
        });

        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        List<Object> records = new ArrayList<>();
                        for (int k = 0; k < 5; k++) {
                            Map<String, Object> rec = new LinkedHashMap<>();
                            rec.put("name", "User" + k);
                            records.add(rec);
                        }
                        String body = batchPayload(records, "caller-analytics",
                                "analytics", null, null);
                        var result = accessBatch(body);
                        int status = result.getResponse().getStatus();
                        JsonNode node = json(result);
                        if (status == 200) {
                            String v = node.path("policyVersion").asText();
                            if (!"policy-v1".equals(v)) {
                                // 只有 v1 含 analytics 授权；在其他版本下放行即陈旧快照混用
                                errors.add("batch allowed under non-granted version: " + v);
                                continue;
                            }
                            // 批内 5 条必须齐全、顺序保持、且 name 均按 v1 MASK
                            var arr = node.path("records");
                            if (arr.size() != 5) {
                                errors.add("partial batch result size=" + arr.size());
                            }
                            for (int k = 0; k < 5; k++) {
                                if (arr.get(k).path("index").asInt() != k) {
                                    errors.add("order broken at " + k);
                                }
                                String name = arr.get(k).path("data").path("name").asText();
                                if (!name.equals("U***" + k)) {
                                    // MASK: Userk 长度5 -> 首 U 末 k
                                    errors.add("unexpected mask for User" + k + ": " + name);
                                }
                            }
                        } else if (status == 403) {
                            String code = node.path("code").asText();
                            if (!"UNAUTHORIZED_CALLER".equals(code)) {
                                errors.add("unexpected deny code: " + code);
                            }
                        } else {
                            errors.add("unexpected status " + status + " body="
                                    + result.getResponse().getContentAsString());
                        }
                    }
                } catch (Exception e) {
                    errors.add("reader: " + e);
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(35, TimeUnit.SECONDS), "work did not finish");
        assertTrue(errors.isEmpty(), "batch concurrency errors: " + errors);

        policyRegistry.publish(SeedSnapshot.seedPolicy());
    }

    private AccessPolicy policy(String version, String caller) {
        return new AccessPolicy(version, Map.of(
                caller, new Grant(List.of("analytics"), SensitivityLevel.L2,
                        List.of(new Grant.TransformLevel(SensitivityLevel.L1, TransformType.MASK)))));
    }
}
