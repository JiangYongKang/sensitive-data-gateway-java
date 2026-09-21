package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import com.github.highcumontoa.sensitivedatagatewayjava.error.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.model.CallerPolicy;
import com.github.highcumontoa.sensitivedatagatewayjava.model.ClassificationDefinition;
import com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode;
import com.github.highcumontoa.sensitivedatagatewayjava.model.PolicySnapshot;
import com.github.highcumontoa.sensitivedatagatewayjava.model.PolicyVersion;
import com.github.highcumontoa.sensitivedatagatewayjava.model.SensitivityLevel;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.InMemoryPolicyStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PolicyStoreConcurrencyTest {

    private PolicyVersion version(String v) {
        return new PolicyVersion(v, 0L,
                List.of(new ClassificationDefinition("user.name",
                        SensitivityLevel.L1, true, List.of("String"))),
                List.of(new CallerPolicy("c", false, List.of())));
    }

    @Test
    void snapshotIsAlwaysCompleteNeverHalfUpdated() throws Exception {
        InMemoryPolicyStore store = new InMemoryPolicyStore();
        store.replaceAll(List.of(version("v1")), "v1");

        int threads = 8;
        int iterations = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

        // 多线程并发发布新版本
        for (int t = 0; t < threads / 2; t++) {
            final int base = t;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        String v = "w-" + base + "-" + i;
                        try {
                            store.publish(version(v));
                        } catch (GatewayException ignore) {
                            // 重复版本等预期拒绝
                        }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        // 多线程并发读取：每次读到的快照必须自洽（最新版本必须存在于版本表）
        for (int t = 0; t < threads / 2; t++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        PolicySnapshot snapshot = store.snapshot();
                        if (!snapshot.versions().containsKey(snapshot.latestVersion())) {
                            failures.incrementAndGet();
                        }
                        if (snapshot.versions().get(snapshot.latestVersion()) == null) {
                            failures.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (var f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(0, failures.get(), "不得观察到半更新/自相矛盾的快照");
        assertTrue(store.snapshot().versions().size() >= 1);
    }

    @Test
    void replaceAllFailureKeepsExistingSnapshot() {
        InMemoryPolicyStore store = new InMemoryPolicyStore();
        store.replaceAll(List.of(version("v1")), "v1");

        assertThrows(GatewayException.class,
                () -> store.replaceAll(List.of(), null),
                "空替换必须被拒绝");
        assertEquals("v1", store.snapshot().latestVersion(), "失败后保留原快照");

        PolicyVersion dup = new PolicyVersion("v1", 0L, List.of(), List.of());
        assertThrows(GatewayException.class,
                () -> store.replaceAll(List.of(version("v1"), dup), "v1"));
        assertEquals("v1", store.snapshot().latestVersion(), "重复版本失败后保留原快照");
    }

    @Test
    void duplicatePublishRejected() {
        InMemoryPolicyStore store = new InMemoryPolicyStore();
        store.replaceAll(List.of(version("v1")), "v1");
        GatewayException ex = assertThrows(GatewayException.class,
                () -> store.publish(version("v1")));
        assertEquals(DecisionCode.BAD_REQUEST, ex.getCode());
    }

    @Test
    void revocationTakesEffectImmediatelyInNewSnapshot() {
        InMemoryPolicyStore store = new InMemoryPolicyStore();
        store.replaceAll(List.of(version("v1")), "v1");
        assertFalse(store.snapshot().versions().get("v1").callers().get(0).revoked());

        PolicyVersion v2 = new PolicyVersion("v2", 0L,
                List.of(new ClassificationDefinition("user.name",
                        SensitivityLevel.L1, true, List.of("String"))),
                List.of(new CallerPolicy("c", true, List.of())));
        store.publish(v2);
        // 新请求按最新版本读取，立即看到撤销
        assertTrue(store.snapshot().latest().callers().get(0).revoked());
        assertEquals("v2", store.snapshot().latestVersion());
        // 历史版本仍可解释
        assertFalse(store.snapshot().versions().get("v1").callers().get(0).revoked());
    }
}
