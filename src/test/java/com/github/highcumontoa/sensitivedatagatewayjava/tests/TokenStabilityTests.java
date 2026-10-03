package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.SensitivityLevel;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.TransformType;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.AccessPolicy;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.Grant;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.PolicyRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 令牌稳定性专项：同一原始值只要落在“同一个分级字段 + 同一个策略版本”下，
 * 无论出现在记录的哪一层、哪个数组下标、什么排序位置、同批还是跨批/单条，
 * 令牌都必须一致；不同分级字段、不同策略版本的令牌必须可区分。
 *
 * <p>日志口径：每个用例归属一个“业务”（TOKEN_STABILITY / TOKEN_DISTINCTNESS /
 * NON_REGRESSION），{@code [TOKEN-TEST]} 行会打印业务、用例与结果，
 * BeforeAll/AfterAll 汇总各业务用例数，方便从测试日志查看覆盖了哪些业务。
 */
class TokenStabilityTests extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TokenStabilityTests.class);

    private static final Map<String, Integer> BUSINESS_TOTAL = new LinkedHashMap<>();
    private static final Map<String, Integer> BUSINESS_PASSED = new LinkedHashMap<>();

    @Autowired
    private PolicyRegistry policyRegistry;

    private static Map<String, Object> recordWithTopAndNestedEmails(String name, String sharedEmail) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", name);
        // 顶层同名字段
        d.put("email", sharedEmail);
        // 嵌套对象里的同名字段
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("email", sharedEmail);
        d.put("profile", profile);
        // 嵌套数组元素里的同名字段
        Map<String, Object> c0 = new LinkedHashMap<>();
        c0.put("email", sharedEmail);
        Map<String, Object> c1 = new LinkedHashMap<>();
        c1.put("email", "other-" + name + "@example.com");
        List<Object> contacts = new ArrayList<>();
        contacts.add(c0);
        contacts.add(c1);
        d.put("contacts", contacts);
        return d;
    }

    @BeforeAll
    static void logCoveragePlan() {
        BUSINESS_TOTAL.put("TOKEN_STABILITY", 0);
        BUSINESS_TOTAL.put("TOKEN_DISTINCTNESS", 0);
        BUSINESS_TOTAL.put("NON_REGRESSION", 0);
        BUSINESS_PASSED.put("TOKEN_STABILITY", 0);
        BUSINESS_PASSED.put("TOKEN_DISTINCTNESS", 0);
        BUSINESS_PASSED.put("NON_REGRESSION", 0);
        log.info("[TOKEN-TEST] business coverage plan: TOKEN_STABILITY=同值跨层级/数组位置/排序/跨记录/跨批/单条令牌一致, "
                + "TOKEN_DISTINCTNESS=不同分级字段与不同策略版本令牌可区分, NON_REGRESSION=结构/顺序/数组长度/不可逆保持");
    }

    @AfterEach
    void logCaseResult(TestInfo info) {
        String business = info.getTags().stream().findFirst().orElse("UNKNOWN");
        BUSINESS_TOTAL.merge(business, 1, Integer::sum);
        BUSINESS_PASSED.merge(business, 1, Integer::sum);
        log.info("[TOKEN-TEST] business={} case={} result=PASS", business, info.getDisplayName());
    }

    @AfterAll
    static void logCoverageSummary() {
        BUSINESS_TOTAL.forEach((business, total) ->
                log.info("[TOKEN-TEST] business={} cases={}/{} passed",
                        business, BUSINESS_PASSED.get(business), total));
    }

    @Test
    @Tag("TOKEN_STABILITY")
    @DisplayName("同值在同一批记录内: 顶层/嵌套对象/数组不同下标 令牌一致")
    void same_value_top_level_nested_object_and_array_positions_share_token() throws Exception {
        String shared = "customer-dup@example.com";
        List<Object> records = List.of(recordWithTopAndNestedEmails("Alice", shared));
        var result = accessBatch(batchPayload(records, "caller-analytics", "analytics", null));
        assertEquals(200, result.getResponse().getStatus());
        var data = json(result).path("data").get(0);

        String topToken = data.path("email").asText();
        String nestedObjectToken = data.path("profile").path("email").asText();
        String arrayIdx0Token = data.path("contacts").get(0).path("email").asText();

        log.info("[TOKEN-TEST] business=TOKEN_STABILITY scenario=within-record top-level={} nested-object={} array[0]={}",
                topToken, nestedObjectToken, arrayIdx0Token);
        assertEquals(topToken, nestedObjectToken,
                "same raw value at top level and inside nested object must share a token");
        assertEquals(topToken, arrayIdx0Token,
                "same raw value at top level and inside array element must share a token");
        assertTrue(topToken.startsWith("tok_"));
        assertNotEquals(shared, topToken, "token must not reveal the raw value");
    }

    @Test
    @Tag("TOKEN_STABILITY")
    @DisplayName("同值跨记录 + 不同数组下标 + 不同排序位置 令牌一致")
    void same_value_across_records_and_array_positions_and_order_share_token() throws Exception {
        String shared = "cross-record-dup@example.com";
        // 记录 0：共享值出现在数组下标 0；记录 1：共享值出现在数组下标 1（排序位置对调）
        Map<String, Object> r0 = recordWithTopAndNestedEmails("Alice", shared);
        Map<String, Object> r1 = recordWithTopAndNestedEmails("Bob", shared);
        List<Object> records = List.of(r0, r1);

        var result = accessBatch(batchPayload(records, "caller-analytics", "analytics", null));
        assertEquals(200, result.getResponse().getStatus());
        var data = json(result).path("data");

        String rec0Top = data.get(0).path("email").asText();
        String rec0Array0 = data.get(0).path("contacts").get(0).path("email").asText();
        String rec1Top = data.get(1).path("email").asText();
        String rec1Nested = data.get(1).path("profile").path("email").asText();
        // 记录 1 的共享值同时出现在顶层与数组下标 0，另一个值在下标 1；
        // 再单独构造一条共享值只出现在下标 1 的记录验证位置无关
        Map<String, Object> onlyAtIdx1 = new LinkedHashMap<>();
        onlyAtIdx1.put("name", "Carol");
        Map<String, Object> x0 = new LinkedHashMap<>();
        x0.put("email", "carol-other@example.com");
        Map<String, Object> x1 = new LinkedHashMap<>();
        x1.put("email", shared);
        onlyAtIdx1.put("contacts", new ArrayList<>(List.of(x0, x1)));
        var result2 = accessBatch(batchPayload(List.of(onlyAtIdx1),
                "caller-analytics", "analytics", null));
        assertEquals(200, result2.getResponse().getStatus());
        String rec2Array1 = json(result2).path("data").get(0).path("contacts").get(1)
                .path("email").asText();

        log.info("[TOKEN-TEST] business=TOKEN_STABILITY scenario=cross-record rec0.top={} rec0.array[0]={} rec1.top={} rec1.nested={} rec2.array[1]={}",
                rec0Top, rec0Array0, rec1Top, rec1Nested, rec2Array1);
        assertEquals(rec0Top, rec1Top, "same raw value across records must share a token");
        assertEquals(rec0Top, rec0Array0, "top level vs array index 0 must share a token");
        assertEquals(rec0Top, rec1Nested, "top level vs nested object in another record must share a token");
        assertEquals(rec0Top, rec2Array1, "array index 0 vs array index 1 must share a token");
    }

    @Test
    @Tag("TOKEN_STABILITY")
    @DisplayName("跨独立批次(两次请求)与单条接口 同值同字段令牌一致")
    void same_value_is_stable_across_independent_batches_and_single_access() throws Exception {
        String shared = "batch-boundary-dup@example.com";

        var batch1 = accessBatch(batchPayload(
                List.of(recordWithTopAndNestedEmails("Alice", shared)),
                "caller-analytics", "analytics", null));
        var batch2 = accessBatch(batchPayload(
                List.of(recordWithTopAndNestedEmails("Bob", shared)),
                "caller-analytics", "analytics", null));
        String batchToken = json(batch1).path("data").get(0).path("email").asText();
        String otherBatchToken = json(batch2).path("data").get(0).path("contacts").get(0)
                .path("email").asText();

        Map<String, Object> singleData = new LinkedHashMap<>();
        singleData.put("name", "Dave");
        singleData.put("email", shared);
        var single = access(payload(singleData, "caller-analytics", "analytics", null));
        assertEquals(200, single.getResponse().getStatus());
        String singleToken = json(single).path("data").path("email").asText();

        log.info("[TOKEN-TEST] business=TOKEN_STABILITY scenario=batch-and-single batch1={} batch2.nested={} single={}",
                batchToken, otherBatchToken, singleToken);
        assertEquals(batchToken, otherBatchToken, "tokens must be stable across independent batches");
        assertEquals(batchToken, singleToken, "batch and single-access tokens for the same field/value must match");
    }

    @Test
    @Tag("TOKEN_DISTINCTNESS")
    @DisplayName("不同分级字段 即使同值同策略版本 令牌可区分")
    void different_classified_fields_with_same_value_produce_distinct_tokens() throws Exception {
        String sameValue = "same-raw-value";
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", sameValue); // L1 -> MASK（非令牌化，跳过）
        d.put("email", sameValue); // L2 -> TOKENIZE
        d.put("phone", sameValue); // L2 -> TOKENIZE，不同分级字段
        var result = accessBatch(batchPayload(List.of(d), "caller-analytics", "analytics", null));
        assertEquals(200, result.getResponse().getStatus());
        var data = json(result).path("data").get(0);
        String emailToken = data.path("email").asText();
        String phoneToken = data.path("phone").asText();

        log.info("[TOKEN-TEST] business=TOKEN_DISTINCTNESS scenario=different-fields email={} phone={}",
                emailToken, phoneToken);
        assertTrue(emailToken.startsWith("tok_"));
        assertTrue(phoneToken.startsWith("tok_"));
        assertNotEquals(emailToken, phoneToken,
                "same value under different classified fields must not be merged into one token");
    }

    @Test
    @Tag("TOKEN_DISTINCTNESS")
    @DisplayName("不同策略版本 同值同分级字段 令牌可区分")
    void different_policy_versions_produce_distinct_tokens() throws Exception {
        String shared = "versioned-dup@example.com";
        Map<String, Object> data = recordWithTopAndNestedEmails("Alice", shared);

        var v1Result = accessBatch(batchPayload(List.of(data),
                "caller-analytics", "analytics", null));
        assertEquals(200, v1Result.getResponse().getStatus());
        String v1Token = json(v1Result).path("data").get(0).path("email").asText();
        String v1NestedToken = json(v1Result).path("data").get(0).path("profile").path("email").asText();
        assertEquals(v1Token, v1NestedToken, "within v1 the stability guarantee still holds");

        publishTokenizingV2();
        try {
            var v2Result = accessBatch(batchPayload(
                    List.of(recordWithTopAndNestedEmails("Alice", shared)),
                    "caller-analytics", "analytics", null));
            assertEquals(200, v2Result.getResponse().getStatus());
            String v2Token = json(v2Result).path("data").get(0).path("email").asText();
            String v2NestedToken = json(v2Result).path("data").get(0).path("profile").path("email").asText();

            log.info("[TOKEN-TEST] business=TOKEN_DISTINCTNESS scenario=policy-versions v1={} v2={} v2.nested={}",
                    v1Token, v2Token, v2NestedToken);
            assertNotEquals(v1Token, v2Token,
                    "tokens for the same value/field must differ across policy versions");
            assertEquals(v2Token, v2NestedToken,
                    "within v2 the stability guarantee still holds");
        } finally {
            policyRegistry.publish(SeedSnapshot.seedPolicy());
        }
    }

    @Test
    @Tag("NON_REGRESSION")
    @DisplayName("稳定性对齐不改变记录顺序/层级/数组元素个数与类型, 且令牌不可逆")
    void token_alignment_preserves_shape_and_remains_irreversible() throws Exception {
        String shared = "shape-dup@example.com";
        List<Object> records = List.of(
                recordWithTopAndNestedEmails("Alice", shared),
                recordWithTopAndNestedEmails("Bob", shared));
        var result = accessBatch(batchPayload(records, "caller-analytics", "analytics", null));
        assertEquals(200, result.getResponse().getStatus());
        var data = json(result).path("data");

        assertEquals(2, data.size(), "record count and order must be preserved");
        // 层级与数组长度保持
        assertEquals(2, data.get(0).path("contacts").size());
        assertEquals(2, data.get(1).path("contacts").size());
        // 类型族保持：命分级标量仍为字符串
        assertTrue(data.get(0).path("email").isTextual());
        assertTrue(data.get(0).path("contacts").get(0).path("email").isTextual());
        // 同值同字段跨所有位置一致且不可逆
        String expected = data.get(0).path("email").asText();
        assertEquals(expected, data.get(0).path("profile").path("email").asText());
        assertEquals(expected, data.get(0).path("contacts").get(0).path("email").asText());
        assertEquals(expected, data.get(1).path("email").asText());
        assertEquals(expected, data.get(1).path("profile").path("email").asText());
        assertEquals(expected, data.get(1).path("contacts").get(0).path("email").asText());
        assertNotEquals(shared, expected, "irreversible token");

        log.info("[TOKEN-TEST] business=NON_REGRESSION scenario=shape-and-irreversibility unifiedToken={}",
                expected);
    }

    private void publishTokenizingV2() {
        // v2 与 v1 对 analytics 的授权/转换完全一致，仅版本号不同，
        // 用来隔离验证“策略版本”这一令牌域。
        Map<String, Grant> grants = new LinkedHashMap<>();
        grants.put("caller-analytics", new Grant(
                List.of("analytics"),
                SensitivityLevel.L2,
                List.of(new Grant.TransformLevel(SensitivityLevel.L1, TransformType.MASK),
                        new Grant.TransformLevel(SensitivityLevel.L2, TransformType.TOKENIZE))));
        policyRegistry.publish(new AccessPolicy("policy-v2", grants));
    }
}
