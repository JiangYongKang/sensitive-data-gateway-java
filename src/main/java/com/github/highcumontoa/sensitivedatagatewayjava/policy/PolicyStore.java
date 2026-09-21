package com.github.highcumontoa.sensitivedatagatewayjava.policy;

import com.github.highcumontoa.sensitivedatagatewayjava.model.PolicySnapshot;
import com.github.highcumontoa.sensitivedatagatewayjava.model.PolicyVersion;

/**
 * 版本化策略存储。快照原子替换，读取永不半更新。
 */
public interface PolicyStore {

    /**
     * 获取当前一致快照（始终非空；未加载时应抛出可区分错误而非返回 null）。
     */
    PolicySnapshot snapshot();

    /**
     * 原子发布新版本并使其成为最新版本。
     */
    void publish(PolicyVersion version);

    /**
     * 原子替换全部版本（加载/刷新），整体成功或整体不生效。
     */
    void replaceAll(java.util.List<PolicyVersion> versions, String latestVersion);
}
