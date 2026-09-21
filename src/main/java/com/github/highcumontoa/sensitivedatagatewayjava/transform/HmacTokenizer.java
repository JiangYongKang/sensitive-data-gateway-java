package com.github.highcumontoa.sensitivedatagatewayjava.transform;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 基于 HMAC-SHA256 的不可逆令牌化。
 *
 * <p>特性：
 * <ul>
 *   <li>同一原始值 + 同一策略版本 → 输出稳定一致（确定性）；</li>
 *   <li>密钥按策略版本派生，旧版本令牌在新版本下不复用（版本隔离）；</li>
 *   <li>仅本地主密钥 + 单向 HMAC，无反查途径（不可逆，对外标注 {@code irreversible=true}）。</li>
 * </ul>
 */
@Component
public class HmacTokenizer implements Tokenizer {

    private static final byte[] MASTER_SECRET =
            "local-gateway-token-master-v1".getBytes(StandardCharsets.UTF_8);

    @Override
    public String tokenize(String rawValue, String policyVersion) {
        if (rawValue == null) {
            throw new IllegalArgumentException("令牌化输入不得为 null");
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] versionKey = sha256.digest(
                    join(MASTER_SECRET, policyVersion.getBytes(StandardCharsets.UTF_8)));

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(versionKey, "HmacSHA256"));
            byte[] digest = mac.doFinal(rawValue.getBytes(StandardCharsets.UTF_8));
            return "tok_" + toHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("令牌化失败", e);
        }
    }

    private static byte[] join(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
