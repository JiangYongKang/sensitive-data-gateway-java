package com.github.highcumontoa.sensitivedatagatewayjava.classification;

import com.github.highcumontoa.sensitivedatagatewayjava.error.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.model.ClassificationDefinition;
import com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode;
import com.github.highcumontoa.sensitivedatagatewayjava.model.LocatedField;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 递归扫描 Map/List 嵌套数据，定位敏感字段并校验缺失、类型与深度/规模。
 */
@Component
public class DefaultDataScanner implements DataScanner {

    @Override
    public List<LocatedField> scan(Map<String, Object> payload,
                                   ClassificationRegistry registry,
                                   int maxDepth,
                                   int maxFieldCount) {
        if (payload == null) {
            throw new GatewayException(DecisionCode.FIELD_MISSING,
                    "请求数据缺失：payload 为空");
        }
        if (registry == null || registry.isEmpty()) {
            throw new GatewayException(DecisionCode.POLICY_MISSING,
                    "当前策略未定义任何字段分级，拒绝访问以避免默认放行");
        }

        ScanState state = new ScanState(registry, maxDepth, maxFieldCount, payload);
        state.walk(payload, null, null, null, null, "", 1);
        state.verifyRequired();

        if (!state.errors.isEmpty()) {
            throw state.errors.get(0);
        }
        state.located.sort((a, b) -> a.path().compareTo(b.path()));
        return List.copyOf(state.located);
    }

    private static final class ScanState {
        private final ClassificationRegistry registry;
        private final int maxDepth;
        private final int maxFieldCount;
        private final Object root;
        private final List<LocatedField> located = new ArrayList<>();
        private final List<GatewayException> errors = new ArrayList<>();
        private final Set<String> matchedPatterns = new HashSet<>();
        private int fieldCount = 0;

        private ScanState(ClassificationRegistry registry, int maxDepth,
                          int maxFieldCount, Object root) {
            this.registry = registry;
            this.maxDepth = maxDepth;
            this.maxFieldCount = maxFieldCount;
            this.root = root;
        }

        private void walk(Object node, Object parent, LocatedField.Container ctype,
                          String key, Integer index, String path, int depth) {
            if (depth > maxDepth) {
                errors.add(new GatewayException(DecisionCode.LIMIT_DEPTH_EXCEEDED,
                        "嵌套深度超过上限 " + maxDepth + "（路径: " + path + "）"));
                return;
            }
            // 每个路径先查分级：命中即按标量校验，容器出现在标量位置判格式异常，不穿透递归
            if (!path.isEmpty() && classifyNode(node, parent, ctype, key, index, path)) {
                return;
            }
            if (node instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    String childKey = e.getKey().toString();
                    count(childKey, path);
                    String childPath = path.isEmpty() ? childKey : path + "." + childKey;
                    walk(e.getValue(), map, LocatedField.Container.MAP,
                            childKey, null, childPath, depth + 1);
                }
            } else if (node instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    count("[" + i + "]", path);
                    String childPath = path + "[" + i + "]";
                    walk(list.get(i), list, LocatedField.Container.LIST,
                            null, i, childPath, depth + 1);
                }
            }
        }

        /**
         * @return true 表示该路径已被分级消费（标量或类型异常），不应再向下递归
         */
        private boolean classifyNode(Object value, Object parent, LocatedField.Container ctype,
                                     String key, Integer index, String concretePath) {
            String normalized = PathPatterns.normalize(concretePath);
            Optional<ClassificationDefinition> hit = registry.lookup(normalized);
            if (hit.isPresent()) {
                ClassificationDefinition def = hit.get();
                matchedPatterns.add(def.path());
                if (value instanceof Map || value instanceof List) {
                    errors.add(new GatewayException(DecisionCode.MALFORMED_DATA,
                            "字段 " + normalized + " 应为标量 " + def.valueTypes()
                                    + "，实际为嵌套结构，数据格式异常"));
                    return true;
                }
                validateValue(value, normalized, def);
                if (value != null) {
                    located.add(new LocatedField(root, parent, ctype, key, index,
                            normalized, def.path(), def.level(), value, def.required()));
                }
                return true;
            }
            String leafKey = leafKey(normalized);
            if (SensitiveKeyCatalog.isSensitiveKey(leafKey)) {
                errors.add(new GatewayException(DecisionCode.CLASSIFICATION_UNDEFINED,
                        "字段 " + normalized
                                + " 疑似敏感但当前策略未定义分级，拒绝以避免未受控放行"));
            }
            return false;
        }

        private void count(String name, String parentPath) {
            fieldCount++;
            if (fieldCount > maxFieldCount) {
                errors.add(new GatewayException(DecisionCode.LIMIT_PAYLOAD_TOO_LARGE,
                        "字段数量超过上限 " + maxFieldCount + "（位于: "
                                + (parentPath.isEmpty() ? name : parentPath + "." + name) + "）"));
            }
        }

        private void validateValue(Object value, String path, ClassificationDefinition def) {
            if (value == null) {
                if (def.required()) {
                    errors.add(new GatewayException(DecisionCode.MALFORMED_DATA,
                            "必填敏感字段 " + path + " 的值为 null，数据格式异常"));
                }
                return;
            }
            if (!def.valueTypes().isEmpty()) {
                String actual = value.getClass().getSimpleName();
                if (!def.valueTypes().contains(actual)) {
                    errors.add(new GatewayException(DecisionCode.MALFORMED_DATA,
                            "字段 " + path + " 类型异常：期望 " + def.valueTypes()
                                    + "，实际 " + actual));
                }
            }
        }

        private void verifyRequired() {
            for (ClassificationDefinition def : registry.definitions()) {
                if (def.required() && !def.path().contains("[*]")
                        && !matchedPatterns.contains(def.path())) {
                    errors.add(new GatewayException(DecisionCode.FIELD_MISSING,
                            "必填敏感字段缺失: " + def.path()));
                }
            }
        }

        private static String leafKey(String normalizedPath) {
            int dot = normalizedPath.lastIndexOf('.');
            return dot < 0 ? normalizedPath : normalizedPath.substring(dot + 1);
        }
    }
}
