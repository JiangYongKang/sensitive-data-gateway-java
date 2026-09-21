package com.github.highcumontoa.sensitivedatagatewayjava.classification;

import com.github.highcumontoa.sensitivedatagatewayjava.model.LocatedField;

import java.util.List;
import java.util.Map;

/**
 * 扫描结构化/嵌套数据，定位全部敏感字段并校验缺失、类型与深度。
 * 任何异常都必须以可区分原因抛出，不得静默跳过。
 */
public interface DataScanner {

    /**
     * @param payload       原始数据
     * @param registry      分级注册表
     * @param maxDepth      最大嵌套深度
     * @param maxFieldCount 最大字段数量
     * @return 定位到的敏感字段（按路径排序）
     */
    List<LocatedField> scan(Map<String, Object> payload,
                            ClassificationRegistry registry,
                            int maxDepth,
                            int maxFieldCount);
}
