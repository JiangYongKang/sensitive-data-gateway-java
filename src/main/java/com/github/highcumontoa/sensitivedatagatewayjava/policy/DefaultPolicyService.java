package com.github.highcumontoa.sensitivedatagatewayjava.policy;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayErrorCode;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.SensitivityLevel;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.TransformType;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 默认放行判定（基于给定版本的不可变策略快照）。
 * 判定顺序与可区分原因：
 * <ol>
 *   <li>策略版本缺失：POLICY_NOT_FOUND；</li>
 *   <li>调用方未配置授权：UNAUTHORIZED_CALLER；</li>
 *   <li>授权用途不含请求用途：PURPOSE_MISMATCH；</li>
 *   <li>字段等级高于授权最高等级：LEVEL_NOT_GRANTED；</li>
 *   <li>否则放行，按等级取转换方式。</li>
 * </ol>
 * 绝不默认放行。
 */
@Component
public class DefaultPolicyService implements PolicyService {

    private final PolicyRegistry registry;

    public DefaultPolicyService(PolicyRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Decision evaluate(AccessPolicy policy, AccessRequest request, SensitivityLevel level) {
        if (policy == null) {
            return Decision.deny(GatewayErrorCode.POLICY_NOT_FOUND,
                    "no policy version available for decision");
        }
        Grant grant = policy.grants().get(request.callerId());
        String basisPrefix = "policy=" + policy.version()
                + ", caller=" + request.callerId()
                + ", purpose=" + request.purpose()
                + ", level=" + level;
        if (grant == null) {
            return Decision.deny(GatewayErrorCode.UNAUTHORIZED_CALLER,
                    basisPrefix + " -> caller has no grant");
        }
        List<String> purposes = grant.purposes() == null ? List.of() : grant.purposes();
        if (!purposes.contains(request.purpose())) {
            return Decision.deny(GatewayErrorCode.PURPOSE_MISMATCH,
                    basisPrefix + " -> purpose not permitted, allowed=" + purposes);
        }
        if (grant.maxLevel() == null || level.getRank() > grant.maxLevel().getRank()) {
            return Decision.deny(GatewayErrorCode.LEVEL_NOT_GRANTED,
                    basisPrefix + " -> level exceeds granted max="
                            + (grant.maxLevel() == null ? "none" : grant.maxLevel()));
        }
        TransformType transform = resolveTransform(grant, level);
        return Decision.allow(transform,
                basisPrefix + ", maxLevel=" + grant.maxLevel() + " -> ALLOW transform=" + transform);
    }

    @Override
    public AccessPolicy current() {
        return registry.latest();
    }

    /**
     * 精确匹配该等级；缺省时取不高于该字段等级的最高一档已配置转换（降级必须有依据），
     * 仍无配置则按最保守的 REDACT 处理。
     */
    private TransformType resolveTransform(Grant grant, SensitivityLevel level) {
        if (grant.transforms() != null) {
            for (Grant.TransformLevel tl : grant.transforms()) {
                if (tl.level() == level && tl.transform() != null) {
                    return tl.transform();
                }
            }
            return grant.transforms().stream()
                    .filter(tl -> tl.transform() != null && tl.level().getRank() <= level.getRank())
                    .max(Comparator.comparingInt(tl -> tl.level().getRank()))
                    .map(Grant.TransformLevel::transform)
                    .orElse(TransformType.REDACT);
        }
        return TransformType.REDACT;
    }
}
