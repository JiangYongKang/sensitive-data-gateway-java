package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基础场景：正常放行（掩码/令牌化稳定）、未授权、用途不匹配。
 */
class BasicAccessTests extends AbstractIntegrationTest {

    private Map<String, Object> sampleData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "Alice");
        data.put("email", "alice@example.com");
        data.put("note", "hello");
        return data;
    }

    @Test
    void allowed_fields_masked_and_tokenized_stably() throws Exception {
        String body = payload(sampleData(), "caller-analytics", "analytics", null);

        var r1 = access(body);
        var r2 = access(body);
        assertEquals(200, r1.getResponse().getStatus());

        var n1 = json(r1);
        var n2 = json(r2);
        // L1 name -> MASK：首末保留
        assertEquals("A***e", n1.path("data").path("name").asText());
        // 非敏感字段原样透传
        assertEquals("hello", n1.path("data").path("note").asText());
        // L2 email -> TOKENIZE：稳定且不可逆（不等于原值）
        String tok1 = n1.path("data").path("email").asText();
        String tok2 = n2.path("data").path("email").asText();
        assertTrue(tok1.startsWith("tok_"));
        assertEquals(tok1, tok2);
        assertNotEquals("alice@example.com", tok1);
        // 版本与审计ID存在
        assertEquals("policy-v1", n1.path("policyVersion").asText());
        assertEquals("classification-v1", n1.path("classificationVersion").asText());
        // 转换结果标注不可逆
        var fieldResults = n1.path("fieldResults");
        boolean tokenMarked = false;
        for (var fr : fieldResults) {
            if (fr.path("path").asText().equals("email")) {
                tokenMarked = fr.path("transform").asText().equals("TOKENIZE")
                        && !fr.path("reversible").asBoolean();
            }
        }
        assertTrue(tokenMarked);
    }

    @Test
    void unauthorized_caller_is_distinguishably_denied() throws Exception {
        String body = payload(sampleData(), "caller-unknown", "analytics", null);
        var result = access(body);
        assertEquals(403, result.getResponse().getStatus());
        var node = json(result);
        assertEquals("UNAUTHORIZED_CALLER", node.path("code").asText());
        // 原因里含判定依据（调用方）
        assertTrue(node.path("message").asText().contains("caller-unknown"));
    }

    @Test
    void purpose_mismatch_is_distinguishably_denied() throws Exception {
        String body = payload(sampleData(), "caller-analytics", "customer-support", null);
        var result = access(body);
        assertEquals(403, result.getResponse().getStatus());
        var node = json(result);
        assertEquals("PURPOSE_MISMATCH", node.path("code").asText());
        assertTrue(node.path("message").asText().contains("customer-support"));
    }

    @Test
    void level_not_granted_is_distinguishably_denied() throws Exception {
        Map<String, Object> data = sampleData();
        data.put("ssn", "123-45-6789");
        String body = payload(data, "caller-analytics", "analytics", null);
        var result = access(body);
        assertEquals(403, result.getResponse().getStatus());
        assertEquals("LEVEL_NOT_GRANTED", json(result).path("code").asText());
    }
}
