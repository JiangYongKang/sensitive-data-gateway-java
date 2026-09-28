package com.github.highcumontoa.sensitivedatagatewayjava.domain;

/**
 * 批量场景下单条记录内的字段处理说明。
 * {@code recordIndex} 为记录在整批中的序号（从 0 开始，与输入顺序一致）；
 * {@code path} 为该记录内部的字段路径（不含记录序号，便于按记录内定位）。
 */
public record RecordFieldResult(
        int recordIndex,
        FieldResult field
) {
}
