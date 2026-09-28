package com.github.highcumontoa.sensitivedatagatewayjava.policy;

/**
 * 策略版本注册表：多版本保存、原子切换最新版本。
 */
public interface PolicyRegistry {

    AccessPolicy get(String version);

    /**
     * 该版本是否曾发布并仍保存在注册表中（不抛异常）。
     * 用于区分“从未发布的版本”（POLICY_NOT_FOUND）与
     * “已发布但不是当前版本”（POLICY_VERSION_FALLBACK_REJECTED）。
     */
    boolean exists(String version);

    AccessPolicy latest();

    String latestVersion();

    void publish(AccessPolicy policy);
}
