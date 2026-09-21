package com.github.highcumontoa.sensitivedatagatewayjava.classification;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayErrorCode;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.SensitivityLevel;
import org.springframework.stereotype.Component;

/**
 * 默认字段识别与分级。
 * 键匹配顺序：精确规范路径 -> 裸字段名（最后一段）。
 * 命中 {@code unclassified} 清单时抛出 CLASSIFICATION_UNDEFINED（可区分，绝不静默跳过）。
 */
@Component
public class DefaultClassificationService implements ClassificationService {

    private final ClassificationRegistry registry;

    public DefaultClassificationService(ClassificationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public SensitivityLevel classify(ClassificationDefinition def, String canonicalPath) {
        if (def == null) {
            throw new GatewayException(GatewayErrorCode.POLICY_NOT_FOUND,
                    "classification version not available");
        }
        if (canonicalPath == null || canonicalPath.isBlank()) {
            throw new GatewayException(GatewayErrorCode.BAD_REQUEST, "field path required");
        }
        SensitivityLevel level = def.fieldLevels().get(canonicalPath);
        if (level != null) {
            return level;
        }
        String leaf = leafName(canonicalPath);
        level = def.fieldLevels().get(leaf);
        if (level != null) {
            return level;
        }
        if (def.unclassified().contains(canonicalPath) || def.unclassified().contains(leaf)) {
            throw new GatewayException(GatewayErrorCode.CLASSIFICATION_UNDEFINED,
                    "sensitivity classification is not defined for field: " + canonicalPath);
        }
        return null;
    }

    @Override
    public ClassificationDefinition current() {
        return registry.latest();
    }

    private static String leafName(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }
}
