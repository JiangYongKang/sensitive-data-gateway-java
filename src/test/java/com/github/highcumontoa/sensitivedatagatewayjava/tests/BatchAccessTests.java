package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量访问：整批放行、跨记录令牌稳定、记录内定位、
 * 坏数据/越权/超限的整批拒绝与分类定位。
 */
class BatchAccessTests extends AbstractIntegrationTest {

    private Map<String, Object> record(String name, Object email) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", name);
        if (email != null) {
            d.put("email", email);
        }
        d.put("note", "n-" + name);
        return d;
    }

    @Test
    void batch_allows_all_records_preserving_order_shape_and_types() throws Exception {
        List<Object> records = List.of(
                record("Alice", "alice@example.com"),
                record("Bob", "bob@example.com"));
        var result = accessBatch(batchPayload(records, "caller-analytics", "analytics", null, null));
        assertEquals(200, result.getResponse().getStatus());
        JsonNode node = json(result);
        assertEquals("policy-v1", node.path("policyVersion").asText());
        assertEquals("classification-v1", node.path("classificationVersion").asText());
        assertEquals(2, node.path("recordCount").asInt());

        JsonNode out = node.path("records");
        // 顺序保持
        assertEquals(0, out.get(0).path("index").asInt());
        assertEquals(1, out.get(1).path("index").asInt());
        // 层级/普通字段/类型族保持，敏感字段被转换
        assertEquals("A***e", out.get(0).path("data").path("name").asText());
        assertEquals("n-Alice", out.get(0).path("data").path("note").asText());
        assertTrue(out.get(1).path("data").path("email").asText().startsWith("tok_"));
        // 记录内字段说明可定位（相对路径）
        boolean emailField = out.get(1).path("fieldResults").findValuesAsText("path")
                .stream().anyMatch("email"::equals);
        assertTrue(emailField);
    }

    @Test
    void token_is_stable_across_records_and_array_positions_for_same_value_field_and_policy()
            throws Exception {
        // 同一 email 出现在不同记录、同一记录内多层数组的不同元素位置
        String shared = "same@example.com";
        Map<String, Object> contacts = new LinkedHashMap<>();
        contacts.put("email", List.of(shared, shared));
        Map<String, Object> r1 = record("Carol", shared);
        Map<String, Object> r2 = new LinkedHashMap<>();
        r2.put("name", "Dave");
        r2.put("nested", contacts);
        r2.put("note", "x");

        var result = accessBatch(batchPayload(List.of(r1, r2),
                "caller-analytics", "analytics", null, null));
        assertEquals(200, result.getResponse().getStatus());
        JsonNode root = json(result).path("records");

        String tokRecord0 = root.get(0).path("data").path("email").asText();
        JsonNode arr = root.get(1).path("data").path("nested").path("email");
        assertEquals(2, arr.size());
        String tokArr0 = arr.get(0).asText();
        String tokArr1 = arr.get(1).asText();
        // 数组元素个数保持，且同值同字段跨记录/跨位置令牌恒定
        assertEquals(tokRecord0, tokArr0);
        assertEquals(tokArr0, tokArr1);
        assertNotEquals(shared, tokRecord0);
        // 不同字段同值令牌隔离
        Map<String, Object> otherField = new LinkedHashMap<>();
        otherField.put("name", "Eve");
        otherField.put("phone", shared);
        var other = accessBatch(batchPayload(List.of(record("Eve", "x@y.io"), otherField),
                "caller-analytics", "analytics", null, null));
        JsonNode otherRoot = json(other).path("records");
        String phoneTok = otherRoot.get(1).path("data").path("phone").asText();
        assertNotEquals(tokRecord0, phoneTok);
    }

    @Test
    void bad_data_in_one_record_rejects_entire_batch_with_location() throws Exception {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("name", "Bad");
        bad.put("email", null); // 分级字段为 null -> DATA_MALFORMED
        List<Object> records = List.of(record("Alice", "alice@example.com"), bad);

        var result = accessBatch(batchPayload(records, "caller-analytics", "analytics", null, null));
        assertEquals(400, result.getResponse().getStatus());
        JsonNode node = json(result);
        assertEquals("DATA_MALFORMED", node.path("code").asText());
        assertEquals("DATA", node.path("category").asText());
        assertEquals(1, node.path("recordIndex").asInt());
        assertEquals("email", node.path("fieldPath").asText());
    }

    @Test
    void unauthorized_or_level_denial_rejects_batch_as_authorization_category() throws Exception {
        // caller-analytics 只有 L2：记录中出现 address(L3) -> LEVEL_NOT_GRANTED
        Map<String, Object> r0 = record("Alice", "alice@example.com");
        Map<String, Object> r1 = record("Bob", "bob@example.com");
        r1.put("address", "1 Infinite Loop");
        var result = accessBatch(batchPayload(List.of(r0, r1),
                "caller-analytics", "analytics", null, null));
        assertEquals(403, result.getResponse().getStatus());
        JsonNode node = json(result);
        assertEquals("LEVEL_NOT_GRANTED", node.path("code").asText());
        assertEquals("AUTHORIZATION", node.path("category").asText());
        assertEquals(1, node.path("recordIndex").asInt());
        assertEquals("address", node.path("fieldPath").asText());

        // 未授权调用方：批次级授权失败（首字段即拒绝），仍有定位
        var unauth = accessBatch(batchPayload(List.of(r0),
                "caller-unknown", "analytics", null, null));
        assertEquals(403, unauth.getResponse().getStatus());
        assertEquals("UNAUTHORIZED_CALLER", json(unauth).path("code").asText());
        assertEquals("AUTHORIZATION", json(unauth).path("category").asText());
    }

    @Test
    void missing_required_field_is_reported_against_specific_record() throws Exception {
        Map<String, Object> noName = new LinkedHashMap<>();
        noName.put("email", "x@y.io");
        var result = accessBatch(batchPayload(
                List.of(record("Alice", "a@b.com"), noName),
                "caller-analytics", "analytics", null, null));
        assertEquals(403, result.getResponse().getStatus());
        JsonNode node = json(result);
        assertEquals("FIELD_MISSING", node.path("code").asText());
        assertEquals(1, node.path("recordIndex").asInt());
    }

    @Test
    void batch_size_limit_rejects_before_processing() throws Exception {
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            many.add(record("u" + i, "u" + i + "@example.com"));
        }
        var result = accessBatch(batchPayload(many, "caller-analytics", "analytics", null, null));
        assertEquals(400, result.getResponse().getStatus());
        JsonNode node = json(result);
        assertEquals("BATCH_SIZE_LIMIT_EXCEEDED", node.path("code").asText());
        assertEquals("LIMIT", node.path("category").asText());
        // 批次级限制：recordIndex 为空
        assertTrue(node.path("recordIndex").isMissingNode() || node.path("recordIndex").isNull());
    }

    @Test
    void record_depth_and_size_limits_reject_with_record_index() throws Exception {
        // 深度超限的记录放在第二条：DEPTH_LIMIT_EXCEEDED 定位到该记录
        Map<String, Object> deep = new LinkedHashMap<>();
        Object cur = "Alice";
        for (int i = 0; i < 11; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("child", cur);
            cur = m;
        }
        deep.put("name", "Deep");
        deep.put("blob", cur);
        var result = accessBatch(batchPayload(
                List.of(record("Alice", "a@b.com"), deep),
                "caller-support", "customer-support", null, null));
        assertEquals(400, result.getResponse().getStatus());
        JsonNode node = json(result);
        assertEquals("DEPTH_LIMIT_EXCEEDED", node.path("code").asText());
        assertEquals("LIMIT", node.path("category").asText());
        assertEquals(1, node.path("recordIndex").asInt());
    }

    @Test
    void record_node_size_limit_rejects_with_record_size_code() throws Exception {
        Map<String, Object> huge = new LinkedHashMap<>();
        huge.put("name", "Huge");
        Map<String, Object> plain = new LinkedHashMap<>();
        for (int i = 0; i < 1001; i++) {
            plain.put("p" + i, i);
        }
        huge.put("plain", plain);
        var result = accessBatch(batchPayload(List.of(huge),
                "caller-support", "customer-support", null, null));
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("RECORD_SIZE_LIMIT_EXCEEDED", json(result).path("code").asText());
        assertEquals("LIMIT", json(result).path("category").asText());
        assertEquals(0, json(result).path("recordIndex").asInt());
    }

    @Test
    void empty_batch_and_non_object_records_are_rejected() throws Exception {
        var empty = accessBatch(batchPayload(List.of(),
                "caller-analytics", "analytics", null, null));
        assertEquals(400, empty.getResponse().getStatus());
        assertEquals("BAD_REQUEST", json(empty).path("code").asText());

        List<Object> mixed = new ArrayList<>();
        mixed.add(record("Alice", "a@b.com"));
        mixed.add("not-an-object");
        var bad = accessBatch(batchPayload(mixed, "caller-analytics", "analytics", null, null));
        assertEquals(400, bad.getResponse().getStatus());
        assertEquals("DATA_MALFORMED", json(bad).path("code").asText());
        assertEquals(1, json(bad).path("recordIndex").asInt());
    }
}
