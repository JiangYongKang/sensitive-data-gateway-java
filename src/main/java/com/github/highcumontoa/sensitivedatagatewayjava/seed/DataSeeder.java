package com.github.highcumontoa.sensitivedatagatewayjava.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationDefinition;
import com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationRegistry;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.AccessPolicy;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.PolicyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 本地种子数据：启动时从 classpath 加载初始分级与策略版本。
 * 不依赖任何外部服务或真实凭据。
 */
@Component
@Order(0)
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final PolicyRegistry policyRegistry;
    private final ClassificationRegistry classificationRegistry;
    private final ObjectMapper objectMapper;

    public DataSeeder(PolicyRegistry policyRegistry,
                      ClassificationRegistry classificationRegistry,
                      ObjectMapper objectMapper) {
        this.policyRegistry = policyRegistry;
        this.classificationRegistry = classificationRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ClassificationDefinition classification = objectMapper.readValue(
                    readResource("seed/classification-v1.json"),
                    ClassificationDefinition.class);
            classificationRegistry.publish(classification);

            AccessPolicy policy = objectMapper.readValue(
                    readResource("seed/policy-v1.json"),
                    AccessPolicy.class);
            policyRegistry.publish(policy);

            log.info("seed loaded policy={} classification={}",
                    policy.version(), classification.version());
        } catch (Exception e) {
            throw new IllegalStateException("failed to load seed data", e);
        }
    }

    private byte[] readResource(String path) throws Exception {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        }
    }
}
