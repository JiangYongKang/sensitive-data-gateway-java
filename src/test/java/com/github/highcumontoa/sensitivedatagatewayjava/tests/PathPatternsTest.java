package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import com.github.highcumontoa.sensitivedatagatewayjava.classification.DefaultClassificationRegistry;
import com.github.highcumontoa.sensitivedatagatewayjava.classification.PathPatterns;
import com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationRegistry;
import com.github.highcumontoa.sensitivedatagatewayjava.model.ClassificationDefinition;
import com.github.highcumontoa.sensitivedatagatewayjava.model.SensitivityLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PathPatternsTest {

    @Test
    void normalizeAndMatchWildcard() {
        assertEquals("user.contacts[*].phone",
                PathPatterns.normalize(" user.contacts[*].phone "));
        assertTrue(PathPatterns.matches(
                "user.contacts[*].phone", "user.contacts[0].phone"));
        assertTrue(PathPatterns.matches(
                "user.contacts[*].phone", "user.contacts[12].phone"));
        assertFalse(PathPatterns.matches(
                "user.contacts[*].phone", "user.contacts[0].name"));
        assertFalse(PathPatterns.matches(
                "user.phone", "user.contacts[0].phone"));
    }

    @Test
    void invalidPathRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PathPatterns.normalize("user.phones[0.phone"));
        assertThrows(IllegalArgumentException.class,
                () -> PathPatterns.normalize("user.phones[x].phone"));
    }

    @Test
    void registryExactAndWildcardLookup() {
        ClassificationRegistry registry = DefaultClassificationRegistry.bind(List.of(
                new ClassificationDefinition("user.name", SensitivityLevel.L1,
                        true, List.of("String")),
                new ClassificationDefinition("user.contacts[*].phone",
                        SensitivityLevel.L2, false, List.of("String"))));
        assertEquals(SensitivityLevel.L1,
                registry.lookup("user.name").orElseThrow().level());
        assertEquals(SensitivityLevel.L2,
                registry.lookup("user.contacts[3].phone").orElseThrow().level());
        assertTrue(registry.lookup("user.unknown").isEmpty());
    }

    @Test
    void duplicateExactPathRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DefaultClassificationRegistry.bind(List.of(
                        new ClassificationDefinition("user.name", SensitivityLevel.L1,
                                true, List.of()),
                        new ClassificationDefinition("user.name", SensitivityLevel.L2,
                                false, List.of()))));
    }
}
