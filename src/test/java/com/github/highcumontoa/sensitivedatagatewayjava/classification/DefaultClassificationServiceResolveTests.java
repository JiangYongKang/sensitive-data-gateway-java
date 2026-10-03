package com.github.highcumontoa.sensitivedatagatewayjava.classification;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.SensitivityLevel;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 识别结果 resolve() 的命中间键语义（业务 CLASSIFICATION_FIELD_KEY）：
 * 精确路径命中返回精确键；裸字段名命中（任意层级/数组包装）返回裸字段名键，
 * 该键即令牌的字段域，与出现位置无关。
 */
class DefaultClassificationServiceResolveTests {

    private static final Logger log = LoggerFactory.getLogger(DefaultClassificationServiceResolveTests.class);

    private final DefaultClassificationService service =
            new DefaultClassificationService(stubRegistry());

    private ClassificationRegistry stubRegistry() {
        return new ClassificationRegistry() {
            @Override
            public ClassificationDefinition get(String version) {
                return def();
            }

            @Override
            public ClassificationDefinition latest() {
                return def();
            }

            @Override
            public String latestVersion() {
                return "classification-v1";
            }

            @Override
            public void publish(ClassificationDefinition definition) {
                // stub
            }
        };
    }

    private ClassificationDefinition def() {
        return new ClassificationDefinition(
                "classification-v1",
                Map.of("email", SensitivityLevel.L2,
                        "person.passportNo", SensitivityLevel.L4),
                List.of("taxId"),
                List.of("name"));
    }

    @Test
    void bare_field_key_is_returned_for_top_level_nested_and_array_paths() {
        String top = service.resolve(def(), "email").fieldKey();
        String nested = service.resolve(def(), "contacts[].email").fieldKey();
        String deep = service.resolve(def(), "profile.contacts[].email").fieldKey();
        String arrayTwice = service.resolve(def(), "a.b[].c[].email").fieldKey();
        log.info("[TOKEN-TEST] business=CLASSIFICATION_FIELD_KEY case=bare-key-normalization "
                + "top={} contacts[].email={} profile.contacts[].email={} a.b[].c[].email={}",
                top, nested, deep, arrayTwice);
        assertEquals("email", top);
        assertEquals("email", nested, "nested path must normalize to bare field key");
        assertEquals("email", deep, "deeply nested path must normalize to bare field key");
        assertEquals("email", arrayTwice, "array wrappers must not enter the token field domain");
        assertEquals(SensitivityLevel.L2, service.resolve(def(), "email").level());
    }

    @Test
    void exact_path_key_is_returned_for_exact_match() {
        String key = service.resolve(def(), "person.passportNo").fieldKey();
        log.info("[TOKEN-TEST] business=CLASSIFICATION_FIELD_KEY case=exact-key key={}", key);
        assertEquals("person.passportNo", key);
    }

    @Test
    void unclassified_field_resolves_to_null() {
        FieldClassification resolved = service.resolve(def(), "note");
        log.info("[TOKEN-TEST] business=CLASSIFICATION_FIELD_KEY case=plain-field resolved={}",
                (Object) null);
        assertNull(resolved);
    }
}
