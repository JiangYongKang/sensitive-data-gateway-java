package com.github.highcumontoa.sensitivedatagatewayjava.policy;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.SensitivityLevel;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.TransformType;

import java.util.List;

/**
 * 单条授权：允许的用途、最高可见等级与各等级转换方式。
 */
public record Grant(
        List<String> purposes,
        SensitivityLevel maxLevel,
        List<TransformLevel> transforms
) {
    public record TransformLevel(SensitivityLevel level, TransformType transform) {
    }
}
