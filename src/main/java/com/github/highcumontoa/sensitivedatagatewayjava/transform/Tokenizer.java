package com.github.highcumontoa.sensitivedatagatewayjava.transform;

/**
 * 令牌化器：同一原始值在同一策略版本下输出稳定、不可逆。
 */
public interface Tokenizer {

    /**
     * @param rawValue      原始值
     * @param policyVersion 策略版本（决定密钥/作用域，跨版本不复用令牌）
     * @return 不可逆且稳定的令牌
     */
    String tokenize(String rawValue, String policyVersion);
}
