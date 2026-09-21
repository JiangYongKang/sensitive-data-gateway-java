package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationRegistry;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.PolicyRegistry;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 在种子 DataSeeder（@Order(0)）之后捕获 v1 快照。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class SeedSnapshotInitializer implements ApplicationRunner {

    private final PolicyRegistry policyRegistry;
    private final ClassificationRegistry classificationRegistry;

    public SeedSnapshotInitializer(PolicyRegistry policyRegistry,
                                   ClassificationRegistry classificationRegistry) {
        this.policyRegistry = policyRegistry;
        this.classificationRegistry = classificationRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        SeedSnapshot.capture(policyRegistry.get("policy-v1"),
                classificationRegistry.get("classification-v1"));
    }
}
