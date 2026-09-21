package com.github.highcumontoa.sensitivedatagatewayjava.model;

/**
 * 数据扫描中定位到的一个敏感字段实例（携带父容器引用以便原地转换）。
 *
 * @param root      数据根
 * @param parent    父容器（Map 或 List）
 * @param container 父容器类型
 * @param key       Map 键（container=MAP 时有值）
 * @param index     List 下标（container=LIST 时有值）
 * @param path      归一化具体路径
 * @param pattern   命中的分级定义路径（可能含 [*]）
 * @param level     敏感等级
 * @param value     原始值
 * @param required  是否必填
 */
public record LocatedField(
        Object root,
        Object parent,
        Container container,
        String key,
        Integer index,
        String path,
        String pattern,
        SensitivityLevel level,
        Object value,
        boolean required) {

    public enum Container {MAP, LIST}

    /** 在父容器中原地写入转换后的值。 */
    public void writeConverted(Object converted) {
        if (container == Container.MAP) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = (java.util.Map<String, Object>) parent;
            map.put(key, converted);
        } else {
            @SuppressWarnings("unchecked")
            java.util.List<Object> list = (java.util.List<Object>) parent;
            list.set(index, converted);
        }
    }
}
