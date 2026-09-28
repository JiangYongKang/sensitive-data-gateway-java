package com.github.highcumontoa.sensitivedatagatewayjava.policy;

import com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationDefinition;

/**
 * 一次请求（单条或整批）解析后固定下来的不可变版本快照对。
 * <p>请求处理全程只使用这对快照，期间发生策略/分级发布或授权变更均不影响本请求，
 * 杜绝一批内混用新旧规则、响应版本与审计版本不一致。
 */
public record VersionSnapshot(
        AccessPolicy policy,
        ClassificationDefinition classification
) {
}
