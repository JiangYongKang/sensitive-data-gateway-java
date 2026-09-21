package com.github.highcumontoa.sensitivedatagatewayjava.policy;

/**
 * 策略版本注册表：多版本保存、原子切换最新版本。
 */
public interface PolicyRegistry {

    AccessPolicy get(String version);

    AccessPolicy latest();

    String latestVersion();

    void publish(AccessPolicy policy);
}
