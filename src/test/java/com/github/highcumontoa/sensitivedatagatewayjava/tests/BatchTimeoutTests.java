package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationService;
import com.github.highcumontoa.sensitivedatagatewayjava.config.GatewayProperties;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.BatchAccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayErrorCode;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.SensitivityLevel;
import com.github.highcumontoa.sensitivedatagatewayjava.engine.BatchDataProcessingEngine;
import com.github.highcumontoa.sensitivedatagatewayjava.engine.RecordProcessor;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.AccessPolicy;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.Grant;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.PolicyService;
import com.github.highcumontoa.sensitivedatagatewayjava.transform.DefaultTransformerFactory;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 整批耗时预算：批内每条记录共享同一个起始时刻，超限时以 BATCH_TIMEOUT_EXCEEDED 整批拒绝。
 */
class BatchTimeoutTests {

    @Test
    void batch_timeout_rejects_with_batch_timeout_code() {
        GatewayProperties props = new GatewayProperties();
        props.setMaxBatchRecords(100);
        props.setMaxBatchRecordNodes(1000);
        props.setBatchTimeoutMs(-1L); // 立即超时

        // 用一个在分类时休眠的分级服务触发超时
        ClassificationService slowClassification = new ClassificationService() {
            @Override
            public SensitivityLevel classify(
                    com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationDefinition def,
                    String canonicalPath) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }

            @Override
            public com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationDefinition current() {
                return null;
            }
        };
        PolicyService policyService = new PolicyService() {
            @Override
            public Decision evaluate(AccessPolicy policy, AccessRequest request, SensitivityLevel level) {
                return Decision.allow(com.github.highcumontoa.sensitivedatagatewayjava.domain.TransformType.NONE,
                        "ok");
            }

            @Override
            public AccessPolicy current() {
                return null;
            }
        };
        RecordProcessor processor = new RecordProcessor(slowClassification, policyService,
                new DefaultTransformerFactory());
        BatchDataProcessingEngine engine = new BatchDataProcessingEngine(processor, props);

        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("note", "v");
        BatchAccessRequest batch = new BatchAccessRequest("c", "p", List.of(),
                null, null, List.of(rec));
        AccessPolicy policy = new AccessPolicy("policy-x",
                Map.of("c", new Grant(List.of("p"), SensitivityLevel.L4, List.of())));
        var classification =
                new com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationDefinition(
                        "classification-x", Map.of(), List.of(), List.of());

        GatewayException ex = assertThrows(GatewayException.class,
                () -> engine.process(batch, policy, classification));
        assertEquals(GatewayErrorCode.BATCH_TIMEOUT_EXCEEDED, ex.getCode());
        assertTrue(ex.getMessage().contains("batch record index 0"));
        assertEquals(0, ex.getRecordIndex());
    }
}
