package com.github.highcumontoa.sensitivedatagatewayjava.model;

import java.util.List;

/**
 * 一个不可变的策略版本：含分级定义与调用方策略。
 */
public record PolicyVersion(
        String version,
        long createdAtEpochMillis,
        List<ClassificationDefinition> classifications,
        List<CallerPolicy> callers) {
}
