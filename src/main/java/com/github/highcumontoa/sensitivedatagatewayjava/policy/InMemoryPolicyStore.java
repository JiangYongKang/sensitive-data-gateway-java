package com.github.highcumontoa.sensitivedatagatewayjava.policy;

import com.github.highcumontoa.sensitivedatagatewayjava.error.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode;
import com.github.highcumontoa.sensitivedatagatewayjava.model.PolicySnapshot;
import com.github.highcumontoa.sensitivedatagatewayjava.model.PolicyVersion;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内存策略存储：以 {@link AtomicReference} 发布不可变 {@link PolicySnapshot}，
 * 读线程要么看到完整旧快照，要么看到完整新快照，绝不出现半更新状态。
 *
 * <p>撤销授权随新版本快照立即生效；读取始终取最新引用，不存在陈旧缓存窗口。
 */
@Component
public class InMemoryPolicyStore implements PolicyStore {

    private final AtomicReference<PolicySnapshot> snapshot = new AtomicReference<>();

    @Override
    public PolicySnapshot snapshot() {
        PolicySnapshot current = snapshot.get();
        if (current == null) {
            throw new GatewayException(DecisionCode.POLICY_MISSING,
                    "策略尚未加载完成，拒绝访问以避免默认放行");
        }
        return current;
    }

    @Override
    public synchronized void publish(PolicyVersion version) {
        validate(version);
        PolicySnapshot current = snapshot.get();
        Map<String, PolicyVersion> merged = new HashMap<>();
        if (current != null) {
            merged.putAll(current.versions());
        }
        if (merged.putIfAbsent(version.version(), version) != null) {
            throw new GatewayException(DecisionCode.BAD_REQUEST,
                    "策略版本已存在，禁止重复发布: " + version.version());
        }
        String latest = pickLatest(merged.keySet().stream().toList());
        snapshot.set(new PolicySnapshot(latest, Map.copyOf(merged)));
    }

    @Override
    public synchronized void replaceAll(List<PolicyVersion> versions, String latestVersion) {
        if (versions == null || versions.isEmpty()) {
            throw new GatewayException(DecisionCode.BAD_REQUEST,
                    "替换策略不得为空，操作被拒绝以保留现有版本");
        }
        Map<String, PolicyVersion> map = new HashMap<>();
        for (PolicyVersion v : versions) {
            validate(v);
            if (map.putIfAbsent(v.version(), v) != null) {
                throw new GatewayException(DecisionCode.BAD_REQUEST,
                        "策略版本重复: " + v.version());
            }
        }
        String resolvedLatest =
                (latestVersion != null && map.containsKey(latestVersion))
                        ? latestVersion : pickLatest(map.keySet().stream().toList());
        // 先完整构建不可变快照，再一次性替换引用：失败不影响在线快照
        snapshot.set(new PolicySnapshot(resolvedLatest, Map.copyOf(map)));
    }

    private void validate(PolicyVersion version) {
        if (version == null || version.version() == null || version.version().isBlank()) {
            throw new GatewayException(DecisionCode.BAD_REQUEST, "策略缺少版本号");
        }
        if (version.classifications() == null || version.callers() == null) {
            throw new GatewayException(DecisionCode.BAD_REQUEST,
                    "策略版本 " + version.version() + " 内容不完整");
        }
    }

    /**
     * 选择最新版本：优先按 createdAtEpochMillis 升序，时间相同按版本号字典序。
     */
    static String pickLatest(List<String> versions) {
        return versions.stream()
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new GatewayException(DecisionCode.POLICY_MISSING,
                        "不存在任何策略版本"));
    }
}
