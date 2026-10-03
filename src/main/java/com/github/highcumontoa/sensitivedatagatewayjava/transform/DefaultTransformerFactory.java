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
 * 密钥由固定本地根盐 + 策略版本 + 分级字段键派生。分级字段键只标识“同一个分级字段”
 * （分级定义中的精确路径键或裸字段名键），不含嵌套层级、数组下标、记录序号与排序位置，
 * 因此同一原始值只要落在同一分级字段、同一策略版本下，无论出现在记录的哪一层、
 * 哪个数组位置、哪条记录，输出令牌都恒定；不同分级字段同值令牌不同；
 * 策略版本变更后令牌随之改变，避免跨版本关联。
 * 全部为本地实现，不依赖外部服务或真实凭据。
 */
@Component
public class DefaultTransformerFactory implements TransformerFactory {

    private static final String LOCAL_ROOT_SALT = "local-sensitive-data-gateway|v1";

    @Override
    public ValueTransformer create(TransformType type, String policyVersion, String fieldKey) {
        if (type == null) {
            throw new GatewayException(GatewayErrorCode.INTERNAL_ERROR, "transform type required");
        }
        return switch (type) {
            case NONE -> new IdentityTransformer();
            case MASK -> new MaskTransformer();
            case REDACT -> new RedactTransformer();
            case TOKENIZE -> new TokenizeTransformer(policyVersion, fieldKey);
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

        private TokenizeTransformer(String policyVersion, String fieldKey) {
            String keyMaterial = LOCAL_ROOT_SALT + "|policy=" + policyVersion + "|field=" + fieldKey;
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
