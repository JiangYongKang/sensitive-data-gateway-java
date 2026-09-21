package com.github.highcumontoa.sensitivedatagatewayjava.model;

import java.util.Set;

/**
 * 单个字段路径的授权：允许的用途与该等级下允许的最高处理方式。
 */
public record FieldGrant(
        String fieldPath,
        Set<String> allowedPurposes,
        TransformType transform) {
}
