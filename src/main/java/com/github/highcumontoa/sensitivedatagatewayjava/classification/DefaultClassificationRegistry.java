package com.github.highcumontoa.sensitivedatagatewayjava.classification;

import com.github.highcumontoa.sensitivedatagatewayjava.model.ClassificationDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 分级注册表工厂：为某个策略版本构建不可变注册表。
 */
public final class DefaultClassificationRegistry {

    private DefaultClassificationRegistry() {
    }

    public static ClassificationRegistry bind(List<ClassificationDefinition> rawDefinitions) {
        List<ClassificationDefinition> definitions = new ArrayList<>();
        if (rawDefinitions != null) {
            for (ClassificationDefinition d : rawDefinitions) {
                if (d.path() == null || d.path().isBlank() || d.level() == null) {
                    throw new IllegalArgumentException("分级定义缺少路径或等级: " + d);
                }
                definitions.add(new ClassificationDefinition(
                        PathPatterns.normalize(d.path()),
                        d.level(),
                        d.required(),
                        d.valueTypes() == null ? List.of() : List.copyOf(d.valueTypes())));
            }
        }
        return new RegistryImpl(List.copyOf(definitions));
    }

    private static final class RegistryImpl implements ClassificationRegistry {

        private final List<ClassificationDefinition> definitions;
        private final Map<String, ClassificationDefinition> exact = new HashMap<>();
        private final List<ClassificationDefinition> wildcard = new ArrayList<>();

        private RegistryImpl(List<ClassificationDefinition> definitions) {
            this.definitions = definitions;
            for (ClassificationDefinition d : definitions) {
                if (d.path().contains("[*]")) {
                    wildcard.add(d);
                } else {
                    ClassificationDefinition dup = exact.put(d.path(), d);
                    if (dup != null) {
                        throw new IllegalArgumentException("分级定义路径重复: " + d.path());
                    }
                }
            }
        }

        @Override
        public Optional<ClassificationDefinition> lookup(String normalizedPath) {
            ClassificationDefinition exactHit = exact.get(normalizedPath);
            if (exactHit != null) {
                return Optional.of(exactHit);
            }
            ClassificationDefinition best = null;
            for (ClassificationDefinition d : wildcard) {
                if (PathPatterns.matches(d.path(), normalizedPath)) {
                    if (best == null || d.path().length() > best.path().length()) {
                        best = d;
                    }
                }
            }
            return Optional.ofNullable(best);
        }

        @Override
        public boolean isEmpty() {
            return definitions.isEmpty();
        }

        @Override
        public List<ClassificationDefinition> definitions() {
            return definitions;
        }
    }
}
