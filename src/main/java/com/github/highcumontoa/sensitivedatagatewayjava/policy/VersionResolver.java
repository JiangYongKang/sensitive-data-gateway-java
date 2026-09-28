package com.github.highcumontoa.sensitivedatagatewayjava.policy;

import com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationDefinition;
import com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationRegistry;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayErrorCode;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayException;
import org.springframework.stereotype.Component;

/**
 * 版本解析：把一次请求的版本要求解析为一对不可变快照。
 *
 * <p>可区分的拒绝结果（单条与批量共用）：
 * <ul>
 *   <li>请求的策略版本<b>从未发布过</b>：{@code POLICY_NOT_FOUND}（404）；</li>
 *   <li>请求的策略版本<b>已发布但不是当前最新</b>：
 *       {@code POLICY_VERSION_FALLBACK_REJECTED}（403），绝不拿当前版本代替；</li>
 *   <li>分级版本同理：{@code CLASSIFICATION_VERSION_NOT_FOUND}（404）/
 *       {@code CLASSIFICATION_VERSION_FALLBACK_REJECTED}（403）。</li>
 * </ul>
 * 不指定版本时固定取当前最新快照；解析一次性完成，之后注册表如何发布都不影响本请求。
 */
@Component
public class VersionResolver {

    private final PolicyRegistry policyRegistry;
    private final ClassificationRegistry classificationRegistry;

    public VersionResolver(PolicyRegistry policyRegistry,
                           ClassificationRegistry classificationRegistry) {
        this.policyRegistry = policyRegistry;
        this.classificationRegistry = classificationRegistry;
    }

    public VersionSnapshot resolve(String requestedPolicyVersion,
                                   String requestedClassificationVersion) {
        AccessPolicy policy = resolvePolicy(requestedPolicyVersion);
        ClassificationDefinition classification =
                resolveClassification(requestedClassificationVersion);
        return new VersionSnapshot(policy, classification);
    }

    private AccessPolicy resolvePolicy(String requested) {
        String latestVersion = policyRegistry.latestVersion();
        if (requested == null || requested.isBlank()) {
            return policyRegistry.latest();
        }
        // 先区分“从未发布”与“已发布但非当前”，二者错误码可区分
        AccessPolicy snapshot = policyRegistry.get(requested);
        if (!requested.equals(latestVersion)) {
            throw new GatewayException(GatewayErrorCode.POLICY_VERSION_FALLBACK_REJECTED,
                    "requested policy version " + requested
                            + " is published but not the current version " + latestVersion
                            + "; fallback is rejected and the current version is not substituted");
        }
        return snapshot;
    }

    private ClassificationDefinition resolveClassification(String requested) {
        String latestVersion = classificationRegistry.latestVersion();
        if (requested == null || requested.isBlank()) {
            return classificationRegistry.latest();
        }
        ClassificationDefinition snapshot = classificationRegistry.get(requested);
        if (!requested.equals(latestVersion)) {
            throw new GatewayException(GatewayErrorCode.CLASSIFICATION_VERSION_FALLBACK_REJECTED,
                    "requested classification version " + requested
                            + " is published but not the current version " + latestVersion
                            + "; fallback is rejected and the current version is not substituted");
        }
        return snapshot;
    }
}
