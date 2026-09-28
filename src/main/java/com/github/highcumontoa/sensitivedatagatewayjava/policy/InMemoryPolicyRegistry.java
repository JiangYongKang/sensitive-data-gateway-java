package com.github.highcumontoa.sensitivedatagatewayjava.policy;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayErrorCode;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存策略版本注册表。
 * 并发语义与分级注册表一致：版本快照不可变，最新引用 volatile 切换，
 * 不存在半更新窗口；撤销授权通过发布新版本生效，旧版本仍可用于解释历史审计。
 */
@Component
public class InMemoryPolicyRegistry implements PolicyRegistry {

    private final Map<String, AccessPolicy> versions = new ConcurrentHashMap<>();
    private volatile AccessPolicy latest;

    @Override
    public AccessPolicy get(String version) {
        AccessPolicy policy = versions.get(version);
        if (policy == null) {
            throw new GatewayException(GatewayErrorCode.POLICY_NOT_FOUND,
                    "policy version not found: " + version);
        }
        return policy;
    }

    @Override
    public boolean exists(String version) {
        return version != null && versions.containsKey(version);
    }

    @Override
    public AccessPolicy latest() {
        AccessPolicy snapshot = latest;
        if (snapshot == null) {
            throw new GatewayException(GatewayErrorCode.POLICY_NOT_FOUND, "no policy published");
        }
        return snapshot;
    }

    @Override
    public String latestVersion() {
        return latest().version();
    }

    @Override
    public synchronized void publish(AccessPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        if (policy.version() == null || policy.version().isBlank()) {
            throw new GatewayException(GatewayErrorCode.BAD_REQUEST, "policy version required");
        }
        if (policy.grants() == null) {
            throw new GatewayException(GatewayErrorCode.BAD_REQUEST, "grants required");
        }
        AccessPolicy immutable = new AccessPolicy(policy.version(), Map.copyOf(policy.grants()));
        versions.put(immutable.version(), immutable);
        this.latest = immutable;
    }
}
