package com.github.highcumontoa.sensitivedatagatewayjava.transform;

import com.github.highcumontoa.sensitivedatagatewayjava.model.FieldDecision;
import com.github.highcumontoa.sensitivedatagatewayjava.model.LocatedField;

import java.util.List;

/**
 * 对允许访问的敏感字段原地（深拷贝后）执行脱敏/掩码/令牌化，
 * 保持嵌套层级与类型约束；返回字段标注信息。
 */
public interface DataTransformer {

    /**
     * 在深拷贝的数据上处理，保证失败时不残留部分结果。
     *
     * @param root          数据根（深拷贝）
     * @param locatedFields 定位字段（与 root 中的容器对应）
     * @param decisions     对应判定
     * @param policyVersion 策略版本
     */
    void apply(Object root,
               List<LocatedField> locatedFields,
               List<FieldDecision> decisions,
               String policyVersion);
}
