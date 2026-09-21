package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import com.github.highcumontoa.sensitivedatagatewayjava.classification.DefaultClassificationRegistry;
import com.github.highcumontoa.sensitivedatagatewayjava.classification.DefaultDataScanner;
import com.github.highcumontoa.sensitivedatagatewayjava.error.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.model.ClassificationDefinition;
import com.github.highcumontoa.sensitivedatagatewayjava.model.LocatedField;
import com.github.highcumontoa.sensitivedatagatewayjava.model.SensitivityLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DataScannerTest {

    private final DefaultDataScanner scanner = new DefaultDataScanner();

    private DefaultClassificationRegistryFactory registry(List<ClassificationDefinition> defs) {
        return new DefaultClassificationRegistryFactory(defs);
    }

    record DefaultClassificationRegistryFactory(List<ClassificationDefinition> defs)
            implements com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationRegistry {
        @Override
        public java.util.Optional<ClassificationDefinition> lookup(String normalizedPath) {
            return DefaultClassificationRegistry.bind(defs).lookup(normalizedPath);
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public List<ClassificationDefinition> definitions() {
            return defs;
        }
    }

    private List<ClassificationDefinition> defs() {
        return List.of(
                new ClassificationDefinition("user.name", SensitivityLevel.L1,
                        true, List.of("String")),
                new ClassificationDefinition("user.email", SensitivityLevel.L2,
                        true, List.of("String")),
                new ClassificationDefinition("user.idCard", SensitivityLevel.L4,
                        false, List.of("String")),
                new ClassificationDefinition("user.contacts[*].phone",
                        SensitivityLevel.L2, false, List.of("String")));
    }

    @Test
    void nestedFieldsLocatedWithContainerRefs() {
        Map<String, Object> contact = new java.util.HashMap<>();
        contact.put("phone", "13800000000");
        Map<String, Object> user = new java.util.HashMap<>();
        user.put("name", "Alice");
        user.put("email", "a@example.com");
        user.put("contacts", new java.util.ArrayList<>(List.of(contact)));
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("user", user);
        List<LocatedField> fields = scanner.scan(payload,
                DefaultClassificationRegistry.bind(defs()), 8, 500);
        assertEquals(3, fields.size());
        LocatedField phone = fields.stream()
                .filter(f -> f.path().equals("user.contacts[0].phone")).findFirst().orElseThrow();
        assertEquals(LocatedField.Container.MAP, phone.container());
        phone.writeConverted("MASKED");
        @SuppressWarnings("unchecked")
        Map<String, Object> contactRead = (Map<String, Object>)
                ((java.util.List<?>) ((Map<?, ?>) payload.get("user")).get("contacts")).get(0);
        assertEquals("MASKED", contactRead.get("phone"));
    }

    @Test
    void missingRequiredFieldRejected() {
        Map<String, Object> payload = Map.of("user", Map.of("name", "Alice"));
        GatewayException ex = assertThrows(GatewayException.class,
                () -> scanner.scan(payload,
                        DefaultClassificationRegistry.bind(defs()), 8, 500));
        assertEquals(com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode.FIELD_MISSING,
                ex.getCode());
        assertTrue(ex.getMessage().contains("user.email"));
    }

    @Test
    void sensitiveButUnclassifiedKeyRejected() {
        Map<String, Object> payload = Map.of("user", Map.of(
                "name", "Alice",
                "email", "a@example.com",
                "bankCard", "6222000000000000"));
        GatewayException ex = assertThrows(GatewayException.class,
                () -> scanner.scan(payload,
                        DefaultClassificationRegistry.bind(defs()), 8, 500));
        assertEquals(com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode.CLASSIFICATION_UNDEFINED,
                ex.getCode());
        assertTrue(ex.getMessage().contains("bankCard"));
    }

    @Test
    void wrongValueTypeRejectedAsMalformed() {
        Map<String, Object> payload = Map.of("user", Map.of(
                "name", 12345,
                "email", "a@example.com"));
        GatewayException ex = assertThrows(GatewayException.class,
                () -> scanner.scan(payload,
                        DefaultClassificationRegistry.bind(defs()), 8, 500));
        assertEquals(com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode.MALFORMED_DATA,
                ex.getCode());
        assertTrue(ex.getMessage().contains("user.name"));
    }

    @Test
    void requiredNullValueRejectedAsMalformed() {
        Map<String, Object> payload = new java.util.HashMap<>();
        Map<String, Object> user = new java.util.HashMap<>();
        user.put("name", "Alice");
        user.put("email", null);
        payload.put("user", user);
        GatewayException ex = assertThrows(GatewayException.class,
                () -> scanner.scan(payload,
                        DefaultClassificationRegistry.bind(defs()), 8, 500));
        assertEquals(com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode.MALFORMED_DATA,
                ex.getCode());
    }

    @Test
    void depthLimitEnforced() {
        Map<String, Object> deep = Map.of("user", Map.of(
                "name", "Alice",
                "email", "a@example.com",
                "extra", Map.of("a", Map.of("b", Map.of("c", "d")))));
        GatewayException ex = assertThrows(GatewayException.class,
                () -> scanner.scan(deep,
                        DefaultClassificationRegistry.bind(defs()), 3, 500));
        assertEquals(com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode.LIMIT_DEPTH_EXCEEDED,
                ex.getCode());
    }

    @Test
    void emptyClassificationRejects() {
        GatewayException ex = assertThrows(GatewayException.class,
                () -> scanner.scan(Map.of("a", 1),
                        DefaultClassificationRegistry.bind(List.of()), 8, 500));
        assertEquals(com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode.POLICY_MISSING,
                ex.getCode());
    }
}
