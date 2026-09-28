package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量访问：正常放行、顺序/层级/数组长度保持、跨记录与数组内同值令牌稳定、
 * 记录内路径定位、全有或全无的坏数据/越权/超限拒绝。
 */
class BatchAccessTests extends AbstractIntegrationTest {

    private Map<String, Object> record(String name, String email) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", name);
        d.put("email", email);
        d.put("note", "n-" + name);
        return d;
    }

    private Map<String, Object> recordWithContacts(String name, String email1, String email2) {
        Map<String, Object> d = record(name, "primary-" + email1);
        Map<String, Object> c0 = new LinkedHashMap<>();
        c0.put("email", email1);
        Map<String, Object> c1 = new LinkedHashMap<>();
        c1.put("email", email2);
        List<Object> contacts = new ArrayList<>();
        contacts.add(c0);
        contacts.add(c1);
        d.put("contacts", contacts);
        return d;
    }

    @Test
    void batch_allowed_preserves_order_shape_and_types() throws Exception {
        List<Object> records = List.of(record("Alice", "alice@example.com"),
                record("Bob", "bob@example.com"));
        var result = accessBatch(batchPayload(records, "caller-analytics", "analytics", null));
        assertEquals(200, result.getResponse().getStatus());
        var node = json(result);

        assertEquals("policy-v1", node.path("policyVersion").asText());
        assertEquals("classification-v1", node.path("classificationVersion").asText());
        var data = node.path("data");
        assertEquals(2, data.size());
        // 顺序保持
        assertEquals("A***e", data.get(0).path("name").asText());
        assertEquals("B*b", data.get(1).path("name").asText());
        // 普通字段透传且类型保持（字符串）
        assertEquals("n-Alice", data.get(0).path("note").asText());
        assertTrue(data.get(0).path("email").asText().startsWith("tok_"));

        // 字段说明可按记录序号还原
        var frs = node.path("fieldResults");
        boolean aliceName = false;
        boolean bobEmail = false;
        for (var fr : frs) {
            int idx = fr.path("recordIndex").asInt(-1);
            String path = fr.path("field").path("path").asText();
            if (idx == 0 && path.equals("name")) {
                aliceName = fr.path("field").path("transform").asText().equals("MASK");
            }
            if (idx == 1 && path.equals("email")) {
                bobEmail = fr.path("field").path("transform").asText().equals("TOKENIZE")
                        && !fr.path("field").path("reversible").asBoolean();
            }
        }
        assertTrue(aliceName, "record[0].name field result located");
        assertTrue(bobEmail, "record[1].email token marked irreversible");
    }

    @Test
    void token_is_stable_across_records_for_same_value_and_field() throws Exception {
        String shared = "same@example.com";
        List<Object> records = List.of(record("Alice", shared), record("Bob", shared));
        var result = accessBatch(batchPayload(records, "caller-analytics", "analytics", null));
        assertEquals(200, result.getResponse().getStatus());
        var data = json(result).path("data");
        String t0 = data.get(0).path("email").asText();
        String t1 = data.get(1).path("email").asText();
        assertEquals(t0, t1, "same raw value under same field/policy must tokenize identically");
        assertNotEquals(shared, t0);
    }

    @Test
    void token_is_stable_for_same_value_inside_nested_arrays_regardless_of_position()
            throws Exception {
        // 同一值出现在同一字段的不同数组位置
        List<Object> records = List.of(
                recordWithContacts("Alice", "dup@example.com", "other@example.com"),
                recordWithContacts("Bob", "x@example.com", "dup@example.com"));
        var result = accessBatch(batchPayload(records, "caller-analytics", "analytics", null));
        assertEquals(200, result.getResponse().getStatus());
        var data = json(result).path("data");
        // 数组长度保持
        assertEquals(2, data.get(0).path("contacts").size());
        String at0 = data.get(0).path("contacts").get(0).path("email").asText();
        String at1 = data.get(1).path("contacts").get(1).path("email").asText();
        assertEquals(at0, at1, "same value at different array indices must share a token");
        assertTrue(at0.startsWith("tok_"));
        // 字段路径定位到记录内的规范路径（不含记录序号，数组元素同路径）
        var frs = json(result).path("fieldResults");
        boolean found = false;
        for (var fr : frs) {
            if (fr.path("field").path("path").asText().equals("contacts[].email")) {
                found = true;
            }
        }
        assertTrue(found, "nested array field located via within-record canonical path");
    }

    @Test
    void malformed_record_rejects_whole_batch_with_record_index_and_field() throws Exception {
        Map<String, Object> good = record("Alice", "alice@example.com");
        Map<String, Object> bad = record("Bob", "bob@example.com");
        bad.put("email", null); // 分级字段 null -> DATA_MALFORMED
        List<Object> records = List.of(good, bad, good);

        var result = accessBatch(batchPayload(records, "caller-analytics", "analytics", null));
        assertEquals(400, result.getResponse().getStatus());
        var node = json(result);
        assertEquals("DATA_MALFORMED", node.path("code").asText());
        assertEquals(1, node.path("details").path("recordIndex").asInt());
        assertEquals("email", node.path("details").path("fieldPath").asText());
        // 不返回任何已处理数据
        assertTrue(node.path("data").isMissingNode());
    }

    @Test
    void unauthorized_record_rejects_whole_batch_at_record_and_field() throws Exception {
        List<Object> records = List.of(record("Alice", "alice@example.com"),
                record("Bob", "bob@example.com"));
        var result = accessBatch(batchPayload(records, "caller-unknown", "analytics", null));
        assertEquals(403, result.getResponse().getStatus());
        var node = json(result);
        assertEquals("UNAUTHORIZED_CALLER", node.path("code").asText());
        assertEquals(0, node.path("details").path("recordIndex").asInt());
        assertEquals("name", node.path("details").path("fieldPath").asText());
        assertTrue(node.path("data").isMissingNode());
    }

    @Test
    void level_not_granted_is_located_to_record_and_field() throws Exception {
        Map<String, Object> high = new LinkedHashMap<>();
        high.put("name", "Carol");
        high.put("ssn", "123-45-6789"); // L4，analytics 只到 L2
        List<Object> records = List.of(record("Alice", "alice@example.com"), high);
        var result = accessBatch(batchPayload(records, "caller-analytics", "analytics", null));
        assertEquals(403, result.getResponse().getStatus());
        var node = json(result);
        assertEquals("LEVEL_NOT_GRANTED", node.path("code").asText());
        assertEquals(1, node.path("details").path("recordIndex").asInt());
        assertEquals("ssn", node.path("details").path("fieldPath").asText());
    }

    @Test
    void required_field_missing_is_located_to_record() throws Exception {
        Map<String, Object> noName = new LinkedHashMap<>();
        noName.put("email", "x@y.com");
        List<Object> records = List.of(record("Alice", "alice@example.com"), noName);
        var result = accessBatch(batchPayload(records, "caller-analytics", "analytics", null));
        assertEquals(403, result.getResponse().getStatus());
        var node = json(result);
        assertEquals("FIELD_MISSING", node.path("code").asText());
        assertEquals(1, node.path("details").path("recordIndex").asInt());
    }

    @Test
    void record_must_be_object_and_nonscalar_in_array_is_located() throws Exception {
        List<Object> records = new ArrayList<>();
        records.add(record("Alice", "alice@example.com"));
        records.add(List.of("not-an-object"));
        var result = accessBatch(batchPayload(records, "caller-analytics", "analytics", null));
        assertEquals(400, result.getResponse().getStatus());
        var node = json(result);
        assertEquals("DATA_MALFORMED", node.path("code").asText());
        assertEquals(1, node.path("details").path("recordIndex").asInt());
    }

    @Test
    void too_many_records_rejected_before_processing() throws Exception {
        List<Object> records = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            records.add(record("U" + i, "u" + i + "@example.com"));
        }
        var result = accessBatch(batchPayload(records, "caller-analytics", "analytics", null));
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("BATCH_SIZE_LIMIT_EXCEEDED", json(result).path("code").asText());
    }

    @Test
    void per_record_depth_limit_is_rejected_with_record_index() throws Exception {
        Map<String, Object> deep = new LinkedHashMap<>();
        Map<String, Object> cur = deep;
        for (int i = 0; i < 12; i++) {
            Map<String, Object> child = new LinkedHashMap<>();
            cur.put("child", child);
            cur = child;
        }
        cur.put("name", "Deep");
        List<Object> records = List.of(record("Alice", "alice@example.com"), deep);
        var result = accessBatch(batchPayload(records, "caller-support", "customer-support", null));
        assertEquals(400, result.getResponse().getStatus());
        var node = json(result);
        assertEquals("BATCH_RECORD_DEPTH_EXCEEDED", node.path("code").asText());
        assertEquals(1, node.path("details").path("recordIndex").asInt());
    }

    @Test
    void total_node_budget_across_records_is_enforced() throws Exception {
        // 单批默认总节点上限 50000：构造多条规模之和超限、但单条都合法
        List<Object> records = new ArrayList<>();
        for (int r = 0; r < 60; r++) {
            Map<String, Object> big = new LinkedHashMap<>();
            big.put("name", "U" + r);
            for (int i = 0; i < 900; i++) {
                big.put("plain" + i, i);
            }
            records.add(big);
        }
        var result = accessBatch(batchPayload(records, "caller-support", "customer-support", null));
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("BATCH_SIZE_LIMIT_EXCEEDED", json(result).path("code").asText());
    }

    @Test
    void empty_batch_is_bad_request() throws Exception {
        var result = accessBatch(batchPayload(List.of(), "caller-analytics", "analytics", null));
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("BAD_REQUEST", json(result).path("code").asText());
    }
}
