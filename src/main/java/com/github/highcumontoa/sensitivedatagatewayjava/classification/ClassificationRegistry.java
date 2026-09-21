package com.github.highcumontoa.sensitivedatagatewayjava.classification;

/**
 * 分级版本注册表：支持多版本保存、原子切换最新版本。
 */
public interface ClassificationRegistry {

    ClassificationDefinition get(String version);

    ClassificationDefinition latest();

    String latestVersion();

    void publish(ClassificationDefinition definition);
}
