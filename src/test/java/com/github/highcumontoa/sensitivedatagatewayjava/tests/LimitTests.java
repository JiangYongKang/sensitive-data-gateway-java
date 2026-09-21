package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 嵌套深度、请求规模上限：超限按既定原因拒绝，不返回部分结果。
 * 默认 gateway.max-depth=10、max-payload-nodes=1000。
 */
class LimitTests extends AbstractIntegrationTest {

    private Map<String, Object> nested(int depth) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> cur = root;
        for (int i = 0; i < depth; i++) {
            Map<String, Object> child = new LinkedHashMap<>();
            cur.put("child", child);
            cur = child;
        }
        cur.put("name", "Alice");
        return root;
    }

    @Test
    void depth_exceeding_limit_is_denied() throws Exception {
        // 10 层 child + 带 name 的最内层，深度超过 max-depth=10
        String body = payload(nested(12), "caller-support", "customer-support", null);
        var result = access(body);
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("DEPTH_LIMIT_EXCEEDED", json(result).path("code").asText());
    }

    @Test
    void depth_within_limit_is_processed() throws Exception {
        String body = payload(nested(3), "caller-support", "customer-support", null);
        var result = access(body);
        assertEquals(200, result.getResponse().getStatus());
    }

    @Test
    void size_exceeding_limit_is_denied() throws Exception {
        Map<String, Object> big = new LinkedHashMap<>();
        big.put("name", "Alice");
        // 1200 个非敏感普通节点，仅触发规模上限
        for (int i = 0; i < 1200; i++) {
            big.put("plain" + i, i);
        }
        String body = payload(big, "caller-support", "customer-support", null);
        var result = access(body);
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("SIZE_LIMIT_EXCEEDED", json(result).path("code").asText());
    }

    @Test
    void malformed_json_is_normalized_to_bad_request() throws Exception {
        var result = access("{not valid json");
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("BAD_REQUEST", json(result).path("code").asText());
    }
}
