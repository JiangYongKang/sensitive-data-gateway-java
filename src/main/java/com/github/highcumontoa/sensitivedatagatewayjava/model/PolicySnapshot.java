package com.github.highcumontoa.sensitivedatagatewayjava.model;

/**
 * 策略存储在某一时刻的不可变一致快照：读取永远不会看到半更新状态。
 */
public record PolicySnapshot(
        String latestVersion,
        java.util.Map<String, PolicyVersion> versions) {

    public PolicyVersion latest() {
        return versions.get(latestVersion);
    }
}
