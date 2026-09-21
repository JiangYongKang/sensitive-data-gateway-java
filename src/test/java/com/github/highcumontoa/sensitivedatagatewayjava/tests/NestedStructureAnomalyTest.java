package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.sensitivedatagatewayjava.error.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.model.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode;
import com.github.highcumontoa.sensitivedatagatewayjava.service.DefaultGatewayService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 专门覆盖“嵌套数据结构异常”场景：数组元素标量位置出现对象、
 * 通配字段类型错误、深层必填为 null，均须给出可区分拒绝原因而非静默跳过。
 */
@SpringBootTest
class NestedStructureAnomalyTest {

    @Autowired
    private DefaultGatewayService service;
    @Autowired
    private ObjectMapper objectMapper;

    private Map<String, Object> baseUser() {
        Map<String, Object> contact = new HashMap<>();
        contact.put("name", "Mom");
        contact.put("phone", "13800000000");
        Map<String, Object> user = new HashMap<>();
        user.put("name", "Alice");
        user.put("email", "a@example.com");
        user.put("contacts", new ArrayList<>(List.of(contact)));
        return user;
    }

    private DefaultGatewayService.DeniedException run(Map<String, Object> user) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("user", user);
        AccessRequest request = new AccessRequest(
                "svc-analytics", "analytics", null, payload);
        String raw;
        try {
            raw = objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return assertThrows(DefaultGatewayService.DeniedException.class,
                () -> service.access(request, raw));
    }

    @Test
    void wildcardFieldWrongTypeInsideArrayRejected() {
        Map<String, Object> user = baseUser();
        @SuppressWarnings("unchecked")
        Map<String, Object> contact =
                (Map<String, Object>) ((List<?>) user.get("contacts")).get(0);
        contact.put("phone", 13800000000L); // 应为 String
        DefaultGatewayService.DeniedException denied = run(user);
        assertEquals(DecisionCode.MALFORMED_DATA, denied.getCode());
        assertTrue(denied.getMessage().contains("user.contacts[0].phone"));
        assertTrue(denied.isAudited());
    }

    @Test
    void scalarSlotContainingObjectIsMalformed() {
        // phone 位置放成对象：类型不符且属格式异常
        Map<String, Object> user = baseUser();
        @SuppressWarnings("unchecked")
        Map<String, Object> contact =
                (Map<String, Object>) ((List<?>) user.get("contacts")).get(0);
        contact.put("phone", new HashMap<>(Map.of("country", "86", "number", 123)));
        DefaultGatewayService.DeniedException denied = run(user);
        assertEquals(DecisionCode.MALFORMED_DATA, denied.getCode());
    }

    @Test
    void deeplyNestedTooDeepRejected() {
        Map<String, Object> user = new HashMap<>();
        user.put("name", "Alice");
        user.put("email", "a@example.com");
        Map<String, Object> deep = new HashMap<>();
        Map<String, Object> cur = deep;
        for (int i = 0; i < 12; i++) {
            Map<String, Object> next = new HashMap<>();
            cur.put("k" + i, next);
            cur = next;
        }
        cur.put("leaf", "v");
        user.put("profile", deep);
        DefaultGatewayService.DeniedException denied = run(user);
        assertEquals(DecisionCode.LIMIT_DEPTH_EXCEEDED, denied.getCode());
    }

    @Test
    void unknownSensitiveKeyNestedIsClassificationUndefined() {
        Map<String, Object> user = baseUser();
        user.put("secret", "do-not-leak"); // 内置敏感目录命中但策略未定义
        DefaultGatewayService.DeniedException denied = run(user);
        assertEquals(DecisionCode.CLASSIFICATION_UNDEFINED, denied.getCode());
        assertTrue(denied.getMessage().contains("user.secret"));
    }
}
