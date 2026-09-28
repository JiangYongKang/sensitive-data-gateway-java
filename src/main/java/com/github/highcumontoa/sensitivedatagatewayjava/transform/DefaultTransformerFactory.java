package com.github.highcumontoa.sensitivedatagatewayjava.transform;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayErrorCode;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.TransformType;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * 默认转换器工厂。
 * 稳定性：掩码与脱敏为纯函数字符串处理；令牌化使用 HMAC-SHA256，
 * 密钥由固定本地根盐 + 策略版本 + 字段路径派生，因此同一原始值在同一策略版本、
 * 同一字段下输出恒定；策略版本变更后令牌随之改变，避免跨版本关联。
 * 全部为本地实现，不依赖外部服务或真实凭据。
 */
@Component
public class DefaultTransformerFactory implements TransformerFactory {

    private static final String LOCAL_ROOT_SALT = "local-sensitive-data-gateway|v1";

    @Override
    public ValueTransformer create(TransformType type, String policyVersion, String fieldPath) {
        if (type == null) {
            throw new GatewayException(GatewayErrorCode.INTERNAL_ERROR, "transform type required");
        }
        return switch (type) {
            case NONE -> new IdentityTransformer();
            case MASK -> new MaskTransformer();
            case REDACT -> new RedactTransformer();
            case TOKENIZE -> new TokenizeTransformer(policyVersion, fieldPath);
        };
    }

    private static final class IdentityTransformer implements ValueTransformer {
        @Override
        public Object apply(Object original) {
            return original;
        }

        @Override
        public boolean reversible() {
            return true;
        }

        @Override
        public TransformType type() {
            return TransformType.NONE;
        }
    }

    private static final class MaskTransformer implements ValueTransformer {
        @Override
        public Object apply(Object original) {
            String s = String.valueOf(original);
            int n = s.length();
            if (n == 0) {
                return "";
            }
            if (n <= 2) {
                return "*".repeat(n);
            }
            return s.charAt(0) + "*".repeat(n - 2) + s.charAt(n - 1);
        }

        @Override
        public boolean reversible() {
            return false;
        }

        @Override
        public TransformType type() {
            return TransformType.MASK;
        }
    }

    private static final class RedactTransformer implements ValueTransformer {
        @Override
        public Object apply(Object original) {
            return "***REDACTED***";
        }

        @Override
        public boolean reversible() {
            return false;
        }

        @Override
        public TransformType type() {
            return TransformType.REDACT;
        }
    }

    private static final class TokenizeTransformer implements ValueTransformer {

        private final byte[] keyBytes;

        private TokenizeTransformer(String policyVersion, String fieldPath) {
            // 字段身份归一化：数组下标（[]）与嵌套前缀不属于字段身份，
            // 只保留裸字段名（与分级识别的裸字段名匹配口径一致）。
            // 因此同一原始值在同一字段、同一策略版本下，无论位于哪个记录、
            // 哪个嵌套层级或数组位置都派生出同一密钥/令牌；
            // 不同字段名仍相互隔离，策略版本变更后令牌随之改变。
            String stripped = fieldPath.replace("[]", "");
            int dot = stripped.lastIndexOf('.');
            String fieldIdentity = dot < 0 ? stripped : stripped.substring(dot + 1);
            String keyMaterial = LOCAL_ROOT_SALT + "|policy=" + policyVersion
                    + "|field=" + fieldIdentity;
            this.keyBytes = keyMaterial.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public Object apply(Object original) {
            if (original == null) {
                throw new GatewayException(GatewayErrorCode.DATA_MALFORMED,
                        "null value cannot be tokenized");
            }
            byte[] digest;
            try {
                Mac m = Mac.getInstance("HmacSHA256");
                m.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
                digest = m.doFinal(String.valueOf(original).getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new GatewayException(GatewayErrorCode.INTERNAL_ERROR,
                        "tokenization failed", e);
            }
            // 不可逆：HMAC 单向，输出为确定性令牌
            return "tok_" + HexFormat.of().formatHex(digest).substring(0, 32);
        }

        @Override
        public boolean reversible() {
            return false;
        }

        @Override
        public TransformType type() {
            return TransformType.TOKENIZE;
        }
    }
}
