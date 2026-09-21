package com.github.highcumontoa.sensitivedatagatewayjava.model;

import java.util.List;

/**
 * 调用方策略。revoked=true 表示授权已撤销（不得因缓存陈旧继续生效）。
 */
public record CallerPolicy(
        String callerId,
        boolean revoked,
        List<PurposeRule> purposes) {
}
