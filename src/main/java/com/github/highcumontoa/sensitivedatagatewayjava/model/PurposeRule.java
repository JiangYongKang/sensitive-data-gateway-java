package com.github.highcumontoa.sensitivedatagatewayjava.model;

import java.util.List;
import java.util.Set;

/**
 * 调用方在某一访问用途下的规则。
 */
public record PurposeRule(
        String purpose,
        Set<SensitivityLevel> maxLevels,
        List<FieldGrant> grants) {
}
