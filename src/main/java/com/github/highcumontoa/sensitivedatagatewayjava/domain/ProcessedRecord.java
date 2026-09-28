package com.github.highcumontoa.sensitivedatagatewayjava.domain;

import java.util.List;

/**
 * 批量结果中的单条记录：保留批内序号，data 与原记录保持相同层级、
 * 数组元素个数与类型族；fieldResults 的 path 为该记录内的相对路径。
 */
public record ProcessedRecord(
        int index,
        Object data,
        List<FieldResult> fieldResults
) {
}
