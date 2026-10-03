package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.AccessPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 令牌稳定性（客户数据关联场景）：同一原始值落在同一分级字段、同一策略版本下，
 * 无论出现在顶层、嵌套对象、任意数组下标或哪条记录，令牌必须一致；
 * 不同分级字段、不同策略版本必须可区分；令牌不可逆且批量结构语义不回归。
 */
@DisplayName("业务:令牌稳定性-跨记录/跨层级/跨数组位置同值同令牌")
class TokenStabilityTests extends AbstractIntegrationTest {

    private static final String DUP = "dup@example.com";
    private static final String OTHER = "other@example.com";

    private Map<String, Object> mixedRecord() {
        return Map.of(
                "name", "Alice",
                "email", DUP,
                "profile", Map.of("contact", Map.of("email", DUP)),
                "contacts", List.of(
                        Map.of("email", DUP),
                        Map.of("email", OTHER),
                        Map.of("email", DUP)));
    }

    @Test
    @DisplayName("用例:同一记录内顶层/嵌套对象/数组元素同值同令牌")
    void same_value_same_token_across_nesting_levels_within_one_record() throws Exception {
        var result = accessBatch(batchPayload(List.of(mixedRecord()),
                "caller-analytics", "analytics", null));
        assertEquals(200, result.getResponse().getStatus());
        JsonNode rec = json(result).path("data").get(0);

        String top = rec.path("email").asText();
        String nested = rec.path("profile").path("contact").path("email").asText();
        String arr0 = rec.path("contacts").get(0).path("email").asText();
        String arr2 = rec.path("contacts").get(2).path("email").asText();

        assertTrue(top.startsWith("tok_"));
        assertEquals(top, nested, "顶层与嵌套对象中的同名字段同值必须同令牌");
        assertEquals(top, arr0, "顶层与数组元素中的同名字段同值必须同令牌");
        assertEquals(top, arr2, "同一数组不同下标的同值必须同令牌");
        assertNotEquals(top, rec.path("contacts").get(1).path("email").asText(),
                "不同原始值不得同令牌");
    }

    @Test
    @DisplayName("用例:跨记录同值同令牌-顶层与嵌套混合批次")
    void same_value_same_token_across_records_with_mixed_shapes() throws Exception {
        List<Object> records = List.of(
                Map.of("name", "Alice", "email", DUP),
                Map.of("name", "Bob", "contacts", List.of(Map.of("email", DUP))),
                Map.of("name", "Carol", "profile", Map.of("contact", Map.of("email", DUP))),
                Map.of("name", "Dave", "email", OTHER));
        var result = accessBatch(batchPayload(records, "caller-analytics", "analytics", null));
        assertEquals(200, result.getResponse().getStatus());
        JsonNode data = json(result).path("data");
        assertEquals(4, data.size(), "记录数与顺序必须保持");

        String token = data.get(0).path("email").asText();
        assertEquals(token, data.get(1).path("contacts").get(0).path("email").asText(),
                "记录0顶层与记录1嵌套数组元素同值必须同令牌");
        assertEquals(token, data.get(2).path("profile").path("contact").path("email").asText(),
                "记录0顶层与记录2嵌套对象同值必须同令牌");
        assertNotEquals(token, data.get(3).path("email").asText(), "不同原始值不得同令牌");
    }

    @Test
    @DisplayName("用例:同一原始值不同分级字段令牌可区分")
    void same_value_different_classification_fields_produce_different_tokens() throws Exception {
        var record = Map.of("name", "Alice", "email", DUP, "phone", DUP);
        var result = accessBatch(batchPayload(List.of(record),
                "caller-analytics", "analytics", null));
        assertEquals(200, result.getResponse().getStatus());
        JsonNode rec = json(result).path("data").get(0);
        assertNotEquals(rec.path("email").asText(), rec.path("phone").asText(),
                "同一原始值落在不同分级字段必须产生不同令牌");
    }

    @Test
    @DisplayName("用例:同一原始值不同策略版本令牌可区分")
    void same_value_different_policy_versions_produce_different_tokens() throws Exception {
        var record = Map.of("name", "Alice", "email", DUP);
        var v1 = accessBatch(batchPayload(List.of(record), "caller-analytics", "analytics", null));
        assertEquals(200, v1.getResponse().getStatus());
        String v1Token = json(v1).path("data").get(0).path("email").asText();
        assertEquals("policy-v1", json(v1).path("policyVersion").asText());

        AccessPolicy v2 = new AccessPolicy("policy-v2", SeedSnapshot.seedPolicy().grants());
        policyRegistry.publish(v2);
        try {
            var v2Result = accessBatch(batchPayload(List.of(record),
                    "caller-analytics", "analytics", null));
            assertEquals(200, v2Result.getResponse().getStatus());
            assertEquals("policy-v2", json(v2Result).path("policyVersion").asText());
            String v2Token = json(v2Result).path("data").get(0).path("email").asText();
            assertNotEquals(v1Token, v2Token, "策略版本变更后令牌必须改变，避免跨版本关联");
        } finally {
            policyRegistry.publish(SeedSnapshot.seedPolicy());
        }
    }

    @Test
    @DisplayName("用例:单条与批量对同一原始值产出同一令牌")
    void single_and_batch_share_token_for_same_value() throws Exception {
        var record = Map.of("name", "Alice", "email", DUP,
                "contacts", List.of(Map.of("email", DUP)));
        var single = access(payload(record, "caller-analytics", "analytics", null));
        assertEquals(200, single.getResponse().getStatus());
        String singleToken = json(single).path("data").path("email").asText();

        var batch = accessBatch(batchPayload(List.of(record),
                "caller-analytics", "analytics", null));
        assertEquals(200, batch.getResponse().getStatus());
        JsonNode rec = json(batch).path("data").get(0);
        assertEquals(singleToken, rec.path("email").asText(), "单条与批量同值必须同令牌");
        assertEquals(singleToken, rec.path("contacts").get(0).path("email").asText());
    }

    @Test
    @DisplayName("用例:批量顺序/层级/数组长度/类型保持且令牌不可逆")
    void batch_structure_preserved_and_token_irreversible() throws Exception {
        List<Object> records = List.of(
                mixedRecord(),
                Map.of("name", "Bob", "email", OTHER));
        var result = accessBatch(batchPayload(records, "caller-analytics", "analytics", null));
        assertEquals(200, result.getResponse().getStatus());
        JsonNode body = json(result);
        JsonNode data = body.path("data");

        assertEquals(2, data.size(), "记录顺序与条数必须保持");
        assertEquals("A***e", data.get(0).path("name").asText(), "首条记录必须仍是 Alice 的掩码");
        assertEquals("B*b", data.get(1).path("name").asText(), "次条记录必须仍是 Bob 的掩码");
        assertEquals(3, data.get(0).path("contacts").size(), "数组元素个数必须保持");
        assertTrue(data.get(0).path("contacts").get(0).path("email").isTextual(),
                "令牌必须保持字符串类型族");
        assertTrue(data.get(0).path("profile").path("contact").isObject(), "嵌套层级必须保持");

        String token = data.get(0).path("email").asText();
        assertTrue(token.startsWith("tok_"));
        assertFalse(token.contains(DUP), "令牌不得包含原始值，必须不可逆");

        boolean allIrreversible = true;
        for (JsonNode fr : body.path("fieldResults")) {
            JsonNode field = fr.path("field");
            if ("TOKENIZE".equals(field.path("transform").asText())) {
                allIrreversible &= !field.path("reversible").asBoolean();
                assertTrue(field.path("transformed").asBoolean());
            }
        }
        assertTrue(allIrreversible, "TOKENIZE 字段必须标注 reversible=false");
    }

    @Test
    @DisplayName("用例:失败仍整批拒绝并定位到记录序号与字段位置")
    void failure_still_rejects_whole_batch_with_record_and_field_location() throws Exception {
        List<Object> records = List.of(
                Map.of("name", "Alice", "email", DUP),
                Map.of("name", "Bob", "contacts", List.of(
                        Map.of("email", "ok@example.com"),
                        Map.of("email", OTHER),
                        Map.of("email", java.util.Collections.singletonMap("nested", "x")))),
                Map.of("name", "Carol", "email", OTHER));
        // 把 record[1].contacts[2].email 置为对象（分级字段必须为标量），触发整批拒绝
        var result = accessBatch(batchPayload(records, "caller-analytics", "analytics", null));
        assertEquals(400, result.getResponse().getStatus());
        JsonNode err = json(result);
        assertEquals("DATA_MALFORMED", err.path("code").asText());
        assertEquals(1, err.path("details").path("recordIndex").asInt(), "必须定位到记录序号");
        assertEquals("contacts[2].email", err.path("details").path("fieldPath").asText(),
                "必须定位到记录内具体数组元素位置");
        assertTrue(err.path("message").asText().contains("batch record[1]"));
        assertTrue(json(result).path("data").isMissingNode(), "失败时不得返回任何已处理数据");
    }
}
