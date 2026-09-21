package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import com.github.highcumontoa.sensitivedatagatewayjava.error.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode;
import com.github.highcumontoa.sensitivedatagatewayjava.model.FieldDecision;
import com.github.highcumontoa.sensitivedatagatewayjava.model.LocatedField;
import com.github.highcumontoa.sensitivedatagatewayjava.model.SensitivityLevel;
import com.github.highcumontoa.sensitivedatagatewayjava.model.TransformType;
import com.github.highcumontoa.sensitivedatagatewayjava.transform.DefaultDataTransformer;
import com.github.highcumontoa.sensitivedatagatewayjava.transform.HmacTokenizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TransformerTest {

    private final HmacTokenizer tokenizer = new HmacTokenizer();
    private final DefaultDataTransformer transformer = new DefaultDataTransformer(tokenizer);

    private LocatedField attach(Map<String, Object> root, String key,
                                Object value, String path, String pattern) {
        return new LocatedField(root, root, LocatedField.Container.MAP,
                key, null, path, pattern, SensitivityLevel.L2, value, false);
    }

    @Test
    void tokenizeIsStableWithinVersionButDiffersAcrossVersions() {
        String t1 = tokenizer.tokenize("13800000000", "v1");
        String t2 = tokenizer.tokenize("13800000000", "v1");
        String t3 = tokenizer.tokenize("13800000000", "v2");
        assertEquals(t1, t2, "同一原始值同一策略版本令牌必须稳定");
        assertNotEquals(t1, t3, "不同策略版本必须隔离令牌");
        assertTrue(t1.startsWith("tok_"));
        assertNotEquals("13800000000", t1, "令牌不得泄漏原始值");
    }

    @Test
    void maskKeepsEndsAndRedactIsFixed() {
        Map<String, Object> root = new HashMap<>();
        root.put("phone", "13800000000");
        root.put("email", "a@example.com");
        List<LocatedField> fields = List.of(
                attach(root, "phone", "13800000000", "user.phone", "user.phone"),
                attach(root, "email", "a@example.com", "user.email", "user.email"));
        List<FieldDecision> decisions = List.of(
                new FieldDecision("user.phone", DecisionCode.ALLOW,
                        SensitivityLevel.L2, TransformType.MASK, ""),
                new FieldDecision("user.email", DecisionCode.ALLOW,
                        SensitivityLevel.L2, TransformType.REDACT, ""));
        transformer.apply(root, fields, decisions, "v1");
        assertEquals("1*********0", root.get("phone"));
        assertEquals("[REDACTED]", root.get("email"));
    }

    @Test
    void tokenizeNonStringRejectedByTypeConstraint() {
        Map<String, Object> root = new HashMap<>();
        root.put("score", 800);
        List<LocatedField> fields = List.of(
                new LocatedField(root, root, LocatedField.Container.MAP,
                        "score", null, "user.score", "user.score",
                        SensitivityLevel.L3, 800, false));
        List<FieldDecision> decisions = List.of(
                new FieldDecision("user.score", DecisionCode.ALLOW,
                        SensitivityLevel.L3, TransformType.TOKENIZE, ""));
        GatewayException ex = assertThrows(GatewayException.class,
                () -> transformer.apply(root, fields, decisions, "v1"));
        assertEquals(DecisionCode.MALFORMED_DATA, ex.getCode());
        assertEquals(800, root.get("score"), "失败不得留下部分处理结果");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nestedStructureAndTypesPreserved() {
        Map<String, Object> contact = new HashMap<>();
        contact.put("phone", "13800000000");
        Map<String, Object> user = new HashMap<>();
        user.put("name", "Alice");
        user.put("contacts", new ArrayList<>(List.of(contact)));
        Map<String, Object> root = new HashMap<>();
        root.put("user", user);

        LocatedField f = new LocatedField(root, contact, LocatedField.Container.MAP,
                "phone", null, "user.contacts[0].phone",
                "user.contacts[*].phone", SensitivityLevel.L2, "13800000000", false);
        transformer.apply(root, List.of(f),
                List.of(new FieldDecision("user.contacts[0].phone",
                        DecisionCode.ALLOW, SensitivityLevel.L2, TransformType.MASK, "")),
                "v1");

        List<?> contacts = (List<?>) ((Map<?, ?>) root.get("user")).get("contacts");
        Map<?, ?> back = (Map<?, ?>) contacts.get(0);
        assertEquals("1*********0", back.get("phone"));
        assertEquals("Alice", ((Map<?, ?>) root.get("user")).get("name"));
    }
}
