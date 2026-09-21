package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.sensitivedatagatewayjava.model.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.model.CallerPolicy;
import com.github.highcumontoa.sensitivedatagatewayjava.model.ClassificationDefinition;
import com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode;
import com.github.highcumontoa.sensitivedatagatewayjava.model.FieldGrant;
import com.github.highcumontoa.sensitivedatagatewayjava.model.PolicyVersion;
import com.github.highcumontoa.sensitivedatagatewayjava.model.PurposeRule;
import com.github.highcumontoa.sensitivedatagatewayjava.model.SensitivityLevel;
import com.github.highcumontoa.sensitivedatagatewayjava.model.TransformType;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.PolicyStore;
import com.github.highcumontoa.sensitivedatagatewayjava.service.DefaultGatewayService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发验证：同一策略在被读取时发布“撤销调用方”的新版本，
 * 已撤销授权不得因缓存陈旧继续生效；所有读取结果只能基于某一完整快照。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RevocationConcurrencyIntegrationTest {

    @Autowired
    private DefaultGatewayService service;
    @Autowired
    private PolicyStore store;
    @Autowired
    private ObjectMapper objectMapper;

    private Map<String, Object> payload() {
        Map<String, Object> user = new HashMap<>();
        user.put("name", "Alice");
        user.put("email", "a@example.com");
        Map<String, Object> root = new HashMap<>();
        root.put("user", user);
        return root;
    }

    private AccessRequest request() {
        return new AccessRequest("svc-revoke-test", "analytics", null, payload());
    }

    private PolicyVersion policy(String version, boolean revoked) {
        return new PolicyVersion(version, System.currentTimeMillis(),
                List.of(
                        new ClassificationDefinition("user.name",
                                SensitivityLevel.L1, true, List.of("String")),
                        new ClassificationDefinition("user.email",
                                SensitivityLevel.L2, true, List.of("String"))),
                List.of(new CallerPolicy("svc-revoke-test", revoked,
                        List.of(new PurposeRule("analytics",
                                Set.of(SensitivityLevel.L1, SensitivityLevel.L2),
                                List.of(
                                        new FieldGrant("user.name",
                                                Set.of("analytics"), TransformType.NONE),
                                        new FieldGrant("user.email",
                                                Set.of("analytics"), TransformType.MASK)))))));
    }

    @Test
    void revokedCallerNeverGetsAccessAfterNewSnapshotPublished() throws Exception {
        // 发布一个授权版本作为基线（独立版本号，避免与种子版本冲突）
        String allowVersion = "revoke-test-v1-" + System.nanoTime();
        store.publish(policy(allowVersion, false));
        // 先确认可访问
        assertNotNull(service.access(request(), "{}"));

        // 发布撤销版本
        String revokeVersion = "revoke-test-v2-" + System.nanoTime();
        store.publish(policy(revokeVersion, true));
        assertEquals(revokeVersion, store.snapshot().latestVersion());

        int threads = 8;
        int perThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger unauthorized = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        try {
                            service.access(request(), "{}");
                            allowed.incrementAndGet();
                        } catch (DefaultGatewayService.DeniedException denied) {
                            if (denied.getCode() == DecisionCode.UNAUTHORIZED_CALLER) {
                                unauthorized.incrementAndGet();
                            } else {
                                other.incrementAndGet();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (var f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(0, allowed.get(), "撤销发布后不得有任何成功访问（无陈旧缓存放行）");
        assertEquals(threads * perThread, unauthorized.get(),
                "全部请求必须以 UNAUTHORIZED_CALLER 被拒绝");
        assertEquals(0, other.get(), "不得出现其它原因");
    }
}
