package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationDefinition;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.AccessPolicy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 保存种子 v1 原始不可变快照（由 {@link SeedSnapshotInitializer} 在种子加载后捕获），
 * 供用例间重置，避免并发/版本测试污染共享上下文。
 */
@TestConfiguration
public class SeedSnapshot {

    private static volatile AccessPolicy seedPolicyV1;
    private static volatile ClassificationDefinition seedClassificationV1;

    @Bean
    Supplier<AccessPolicy> seedPolicySupplier() {
        return () -> seedPolicyV1;
    }

    static void capture(AccessPolicy policy, ClassificationDefinition classification) {
        seedPolicyV1 = policy;
        seedClassificationV1 = classification;
    }

    public static AccessPolicy seedPolicy() {
        return new AccessPolicy(seedPolicyV1.version(), Map.copyOf(seedPolicyV1.grants()));
    }

    public static ClassificationDefinition seedClassification() {
        return seedClassificationV1;
    }
}
