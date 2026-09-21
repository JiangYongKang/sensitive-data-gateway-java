package com.github.highcumontoa.sensitivedatagatewayjava.transform;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.TransformType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转换规则单测：稳定性、不可逆标注、层级类型保持所需的字符串契约。
 */
class TransformerTests {

    private final DefaultTransformerFactory factory = new DefaultTransformerFactory();

    @Test
    void mask_keeps_first_and_last_and_is_irreversible() {
        var t = factory.create(TransformType.MASK, "policy-v1", "name");
        assertEquals("A***e", t.apply("Alice"));
        assertEquals("**", t.apply("ab"));
        assertEquals("", t.apply(""));
        assertFalse(t.reversible());
    }

    @Test
    void redact_is_constant_and_irreversible() {
        var t = factory.create(TransformType.REDACT, "policy-v1", "ssn");
        assertEquals("***REDACTED***", t.apply("anything"));
        assertFalse(t.reversible());
    }

    @Test
    void tokenize_is_stable_for_same_policy_and_field() {
        var t1 = factory.create(TransformType.TOKENIZE, "policy-v1", "email");
        var t2 = factory.create(TransformType.TOKENIZE, "policy-v1", "email");
        Object v1 = t1.apply("alice@example.com");
        Object v2 = t2.apply("alice@example.com");
        assertEquals(v1, v2);
        assertTrue(String.valueOf(v1).startsWith("tok_"));
        assertNotEquals("alice@example.com", v1);
        assertFalse(t1.reversible());
    }

    @Test
    void tokenize_differs_across_policy_versions() {
        var oldV = factory.create(TransformType.TOKENIZE, "policy-v1", "email");
        var newV = factory.create(TransformType.TOKENIZE, "policy-v2", "email");
        assertNotEquals(oldV.apply("alice@example.com"), newV.apply("alice@example.com"));
    }

    @Test
    void tokenize_differs_across_fields() {
        var a = factory.create(TransformType.TOKENIZE, "policy-v1", "email");
        var b = factory.create(TransformType.TOKENIZE, "policy-v1", "phone");
        assertNotEquals(a.apply("x"), b.apply("x"));
    }

    @Test
    void identity_is_reversible() {
        var t = factory.create(TransformType.NONE, "policy-v1", "name");
        assertEquals("Alice", t.apply("Alice"));
        assertTrue(t.reversible());
    }

    @Test
    void tokenize_null_rejected_as_malformed() {
        var t = factory.create(TransformType.TOKENIZE, "policy-v1", "email");
        assertThrows(GatewayException.class, () -> t.apply(null));
    }
}
