package com.github.highcumontoa.sensitivedatagatewayjava.policy;

import com.github.highcumontoa.sensitivedatagatewayjava.model.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.model.EvaluationResult;
import com.github.highcumontoa.sensitivedatagatewayjava.model.LocatedField;
import com.github.highcumontoa.sensitivedatagatewayjava.model.PolicyVersion;

import java.util.List;

/**
 * 按调用方、用途、敏感等级与策略版本做放行判定。
 */
public interface PolicyEvaluator {

    /**
     * @param request       请求
     * @param locatedFields 扫描定位到的敏感字段
     * @param version       实际使用的策略版本
     * @param latestVersion 当前最新版本号（用于回退判定说明）
     * @return 判定结果（拒绝原因彼此可区分）
     */
    EvaluationResult evaluate(AccessRequest request,
                              List<LocatedField> locatedFields,
                              PolicyVersion version,
                              String latestVersion);
}
