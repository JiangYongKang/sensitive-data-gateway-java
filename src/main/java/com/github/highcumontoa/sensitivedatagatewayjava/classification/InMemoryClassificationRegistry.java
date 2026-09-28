package com.github.highcumontoa.sensitivedatagatewayjava.classification;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayErrorCode;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存分级版本注册表。
 * 并发语义：已发布版本不可变；{@code latest} 为 volatile 引用，
 * 发布时先放入完整不可变快照再切换引用，读取方不会看到半更新状态。
 */
@Component
public class InMemoryClassificationRegistry implements ClassificationRegistry {

    private final Map<String, ClassificationDefinition> versions = new ConcurrentHashMap<>();
    private volatile ClassificationDefinition latest;

    @Override
    public ClassificationDefinition get(String version) {
        ClassificationDefinition definition = versions.get(version);
        if (definition == null) {
            throw new GatewayException(GatewayErrorCode.CLASSIFICATION_VERSION_NOT_FOUND,
                    "classification version not found: " + version);
        }
        return definition;
    }

    @Override
    public ClassificationDefinition latest() {
        ClassificationDefinition snapshot = latest;
        if (snapshot == null) {
            throw new GatewayException(GatewayErrorCode.CLASSIFICATION_VERSION_NOT_FOUND,
                    "no classification version published");
        }
        return snapshot;
    }

    @Override
    public String latestVersion() {
        return latest().version();
    }

    @Override
    public synchronized void publish(ClassificationDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (definition.version() == null || definition.version().isBlank()) {
            throw new GatewayException(GatewayErrorCode.BAD_REQUEST, "classification version required");
        }
        if (definition.fieldLevels() == null) {
            throw new GatewayException(GatewayErrorCode.BAD_REQUEST, "fieldLevels required");
        }
        // 发布完整不可变快照，再原子切换最新引用
        ClassificationDefinition immutable = new ClassificationDefinition(
                definition.version(),
                Map.copyOf(definition.fieldLevels()),
                definition.unclassified() == null ? List.of() : List.copyOf(definition.unclassified()),
                definition.required() == null ? List.of() : List.copyOf(definition.required()));
        versions.put(immutable.version(), immutable);
        this.latest = immutable;
    }
}
