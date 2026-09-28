package com.github.highcumontoa.sensitivedatagatewayjava.policy;

import com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationDefinition;
import com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationRegistry;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayErrorCode;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayException;
import org.springframework.stereotype.Component;

/**
 * 版本快照解析（请求开始时一次性完成，不写审计）。
 * <p>整批/单条随后全程基于返回的不可变快照判定，期间即使有策略发布或授权变更，
 * 也不会出现混用新旧规则。
 * <ul>
 *   <li>不指定版本：取当前最新版本；</li>
 *   <li>指定版本且等于最新：使用该版本；</li>
 *   <li>指定版本曾发布但不是当前：POLICY_VERSION_FALLBACK_REJECTED（不拿当前版本代替）；</li>
 *   <li>指定版本从未发布：POLICY_NOT_FOUND（与上面可区分）。</li>
 * </ul>
 * 分级始终取请求开始时的最新版本，并以快照固定。
 */
@Component
public class VersionSnapshotResolver {

    private final PolicyRegistry policyRegistry;
    private final ClassificationRegistry classificationRegistry;

    public VersionSnapshotResolver(PolicyRegistry policyRegistry,
                                   ClassificationRegistry classificationRegistry) {
        this.policyRegistry = policyRegistry;
        this.classificationRegistry = classificationRegistry;
    }

    /**
     * @param requestedPolicyVersion 显式要求的策略版本，可为 null/空白（表示最新）
     */
    public Snapshot resolve(String requestedPolicyVersion) {
        AccessPolicy policy;
        if (requestedPolicyVersion == null || requestedPolicyVersion.isBlank()) {
            policy = policyRegistry.latest();
        } else {
            String latestVersion = policyRegistry.latestVersion();
            if (requestedPolicyVersion.equals(latestVersion)) {
                policy = policyRegistry.get(requestedPolicyVersion);
            } else if (policyRegistry.exists(requestedPolicyVersion)) {
                // 已发布但不是当前版本：显式拒绝，绝不静默回退或拿当前版本代替
                throw new GatewayException(GatewayErrorCode.POLICY_VERSION_FALLBACK_REJECTED,
                        "explicit policy version " + requestedPolicyVersion
                                + " is published but not the current version " + latestVersion);
            } else {
                // 从未发布过的版本：与“已发布非当前”可区分
                throw new GatewayException(GatewayErrorCode.POLICY_NOT_FOUND,
                        "policy version has never been published: " + requestedPolicyVersion
                                + " (current=" + latestVersion + ")");
            }
        }
        ClassificationDefinition classification = classificationRegistry.latest();
        return new Snapshot(policy, classification);
    }

    /**
     * 一次请求内固定的不可变版本快照。
     */
    public record Snapshot(AccessPolicy policy, ClassificationDefinition classification) {
    }
}
