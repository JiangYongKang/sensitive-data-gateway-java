package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 分级未定义 / 字段缺失 / 嵌套数据结构异常：原因彼此可区分，绝不静默跳过。
 */
class ClassificationAndMalformedTests extends AbstractIntegrationTest {

    private Map<String, Object> base() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "Alice");
        return data;
    }

    @Test
    void classification_undefined_field_is_denied() throws Exception {
        Map<String, Object> data = base();
        data.put("taxId", "T-0001"); // 种子分级中列入 unclassified
        String body = payload(data, "caller-admin", "audit-review", null);
        var result = access(body);
        assertEquals(403, result.getResponse().getStatus());
        var node = json(result);
        assertEquals("CLASSIFICATION_UNDEFINED", node.path("code").asText());
        // 不返回数据：错误响应没有 data 字段
        assertTrue(node.path("data").isMissingNode());
    }

    @Test
    void required_field_missing_is_denied() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("email", "a@b.com"); // 缺少必备的 name
        String body = payload(data, "caller-analytics", "analytics", null);
        var result = access(body);
        assertEquals(403, result.getResponse().getStatus());
        assertEquals("FIELD_MISSING", json(result).path("code").asText());
    }

    @Test
    void nested_structure_is_preserved_and_transformed() throws Exception {
        Map<String, Object> data = base();
        Map<String, Object> person = new LinkedHashMap<>();
        person.put("passportNo", "P12345678");
        data.put("person", person);
        String body = payload(data, "caller-admin", "audit-review", null);
        var result = access(body);
        assertEquals(200, result.getResponse().getStatus());
        var node = json(result);
        // 层级保持：person 仍是对象，passportNo 是字符串
        assertEquals("***REDACTED***",
                node.path("data").path("person").path("passportNo").asText());
        var fr = node.path("fieldResults");
        assertEquals("person.passportNo", fr.get(fr.size() - 1).path("path").asText());
    }

    @Test
    void nested_malformed_value_type_is_denied() throws Exception {
        Map<String, Object> data = base();
        Map<String, Object> person = new LinkedHashMap<>();
        // ssn 为字符串敏感字段，却给了对象 -> 数据格式异常（不静默跳过/强转）
        person.put("ssn", Map.of("weird", List.of(1, 2)));
        data.put("person", person);
        String body = payload(data, "caller-admin", "audit-review", null);
        var result = access(body);
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("DATA_MALFORMED", json(result).path("code").asText());
    }

    @Test
    void classified_null_scalar_is_malformed() throws Exception {
        Map<String, Object> data = base();
        data.put("email", null);
        String body = payload(data, "caller-analytics", "analytics", null);
        var result = access(body);
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("DATA_MALFORMED", json(result).path("code").asText());
    }

    @Test
    void payload_must_be_object() throws Exception {
        String body = "[1,2,3]";
        // 直接发数组作为整个请求体属于错误 JSON 结构
        var result = access(body);
        assertEquals(400, result.getResponse().getStatus());
    }

    private static void assertTrue(boolean v) {
        org.junit.jupiter.api.Assertions.assertTrue(v);
    }
}
