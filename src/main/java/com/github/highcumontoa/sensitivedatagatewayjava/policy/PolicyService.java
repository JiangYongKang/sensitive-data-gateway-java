package com.github.highcumontoa.sensitivedatagatewayjava.policy;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayErrorCode;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.SensitivityLevel;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.TransformType;

/**
 * 放行判定：依据调用方、用途、敏感等级，基于指定版本的策略快照得出判定结果。
 * 拒绝原因彼此可区分：UNAUTHORIZED_CALLER / PURPOSE_MISMATCH / LEVEL_NOT_GRANTED / POLICY_NOT_FOUND。
 */
public interface PolicyService {

    Decision evaluate(AccessPolicy policy, AccessRequest request, SensitivityLevel level);

    AccessPolicy current();

    /**
     * 判定结果。
     */
    record Decision(boolean allowed, TransformType transform, String basis, GatewayErrorCode denyReason) {
        public static Decision allow(TransformType transform, String basis) {
            return new Decision(true, transform, basis, null);
        }

        public static Decision deny(GatewayErrorCode reason, String basis) {
            return new Decision(false, null, basis, reason);
        }
    }
}
