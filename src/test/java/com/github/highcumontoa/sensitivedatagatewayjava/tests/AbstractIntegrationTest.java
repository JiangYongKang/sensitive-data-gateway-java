package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.AccessPolicy;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.PolicyRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestConfig.class, SeedSnapshot.class})
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected PolicyRegistry policyRegistry;

    /**
     * 策略/分级注册表为上下文共享单例。每个测试前把最新策略重置回种子 v1
     * （v1 一旦加载不会被删除），并关闭审计失败开关，保证用例相互隔离。
     */
    @BeforeEach
    void resetSharedState() {
        TestAuditProbe.fail = false;
        policyRegistry.publish(SeedSnapshot.seedPolicy());
    }

    protected MvcResult access(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/data/access")
                        .contentType("application/json")
                        .content(body))
                .andReturn();
    }

    protected JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected String payload(Object data, String caller, String purpose, String version) throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("callerId", caller);
        node.put("purpose", purpose);
        if (version != null) {
            node.put("policyVersion", version);
        }
        node.set("data", objectMapper.valueToTree(data));
        return objectMapper.writeValueAsString(node);
    }
}
