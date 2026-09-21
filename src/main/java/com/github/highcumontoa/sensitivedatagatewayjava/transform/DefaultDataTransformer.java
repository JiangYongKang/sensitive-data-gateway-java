package com.github.highcumontoa.sensitivedatagatewayjava.transform;

import com.github.highcumontoa.sensitivedatagatewayjava.error.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode;
import com.github.highcumontoa.sensitivedatagatewayjava.model.FieldDecision;
import com.github.highcumontoa.sensitivedatagatewayjava.model.LocatedField;
import com.github.highcumontoa.sensitivedatagatewayjava.model.TransformType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 在深拷贝数据上原地转换。仅处理判定为 ALLOW 的敏感字段；
 * 非 String 类型遇到脱敏/掩码/令牌化视为格式异常（保持类型约束）。
 */
@Component
public class DefaultDataTransformer implements DataTransformer {

    private final Tokenizer tokenizer;

    public DefaultDataTransformer(Tokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    @Override
    public void apply(Object root,
                      List<LocatedField> locatedFields,
                      List<FieldDecision> decisions,
                      String policyVersion) {
        if (locatedFields.size() != decisions.size()) {
            // 内部一致性错误：宁可失败也不得错放或半处理
            throw new GatewayException(DecisionCode.INTERNAL_ERROR,
                    "敏感字段与判定结果数量不一致，拒绝处理");
        }
        for (int i = 0; i < locatedFields.size(); i++) {
            LocatedField field = locatedFields.get(i);
            FieldDecision decision = decisions.get(i);
            if (!field.path().equals(decision.fieldPath())) {
                throw new GatewayException(DecisionCode.INTERNAL_ERROR,
                        "敏感字段与判定结果顺序不一致，拒绝处理");
            }
            if (decision.code() != DecisionCode.ALLOW) {
                continue; // 理论上整批仅在全部放行时才会进入转换
            }
            Object converted = convert(field, decision.transform(), policyVersion);
            field.writeConverted(converted);
        }
    }

    private Object convert(LocatedField field, TransformType type, String policyVersion) {
        Object value = field.value();
        return switch (type) {
            case NONE -> value;
            case TOKENIZE -> {
                if (!(value instanceof String s)) {
                    throw new GatewayException(DecisionCode.MALFORMED_DATA,
                            "字段 " + field.path() + " 非字符串，无法令牌化（类型约束保持）");
                }
                yield tokenizer.tokenize(s, policyVersion);
            }
            case MASK -> {
                requireString(value, field.path(), "掩码");
                yield mask((String) value);
            }
            case REDACT -> {
                requireString(value, field.path(), "脱敏");
                yield redact((String) value);
            }
        };
    }

    private void requireString(Object value, String path, String action) {
        if (!(value instanceof String)) {
            throw new GatewayException(DecisionCode.MALFORMED_DATA,
                    "字段 " + path + " 非字符串，无法" + action + "（类型约束保持）");
        }
    }

    /** 掩码：保留首尾各 1 字符（长度≤2 则保留 1 位），其余以 * 替换，长度保持。 */
    static String mask(String value) {
        int n = value.length();
        if (n <= 2) {
            return "*".repeat(n);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(value.charAt(0));
        sb.append("*".repeat(n - 2));
        sb.append(value.charAt(n - 1));
        return sb.toString();
    }

    /** 脱敏：整体替换为固定占位符（不可逆，内容长度不保留以防长度侧信道）。 */
    static String redact(String value) {
        return "[REDACTED]";
    }
}
