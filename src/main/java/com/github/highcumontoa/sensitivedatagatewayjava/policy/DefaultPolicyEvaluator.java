package com.github.highcumontoa.sensitivedatagatewayjava.policy;

import com.github.highcumontoa.sensitivedatagatewayjava.error.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.model.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode;
import com.github.highcumontoa.sensitivedatagatewayjava.model.EvaluationResult;
import com.github.highcumontoa.sensitivedatagatewayjava.model.FieldDecision;
import com.github.highcumontoa.sensitivedatagatewayjava.model.FieldGrant;
import com.github.highcumontoa.sensitivedatagatewayjava.model.LocatedField;
import com.github.highcumontoa.sensitivedatagatewayjava.model.PolicyVersion;
import com.github.highcumontoa.sensitivedatagatewayjava.model.PolicyVersionResolution;
import com.github.highcumontoa.sensitivedatagatewayjava.model.PurposeRule;
import com.github.highcumontoa.sensitivedatagatewayjava.model.SensitivityLevel;
import com.github.highcumontoa.sensitivedatagatewayjava.model.TransformType;
import com.github.highcumontoa.sensitivedatagatewayjava.model.CallerPolicy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 判定顺序（拒绝原因彼此可区分，且绝不默认放行）：
 * <ol>
 *   <li>显式回退到旧版本 → POLICY_FALLBACK_DENIED；</li>
 *   <li>调用方不存在/已撤销 → UNAUTHORIZED_CALLER；</li>
 *   <li>用途未授权 → PURPOSE_MISMATCH；</li>
 *   <li>字段超过该用途允许的最高等级 → FIELD_LEVEL_EXCEEDED；</li>
 *   <li>敏感字段无显式授权 → POLICY_MISSING；</li>
 *   <li>全部通过 → ALLOW。</li>
 * </ol>
 */
@Component
public class DefaultPolicyEvaluator implements PolicyEvaluator {

    @Override
    public EvaluationResult evaluate(AccessRequest request,
                                     List<LocatedField> locatedFields,
                                     PolicyVersion version,
                                     String latestVersion) {
        PolicyVersionResolution resolution = new PolicyVersionResolution(
                request.policyVersion(), version.version(), latestVersion, false);

        CallerPolicy caller = version.callers().stream()
                .filter(c -> c.callerId().equals(request.callerId()))
                .findFirst()
                .orElse(null);

        if (caller == null || caller.revoked()) {
            String reason = caller == null
                    ? "调用方未在策略中登记: " + request.callerId()
                    : "调用方授权已撤销: " + request.callerId();
            return deny(resolution, DecisionCode.UNAUTHORIZED_CALLER, reason, locatedFields, null);
        }

        PurposeRule rule = caller.purposes() == null ? null : caller.purposes().stream()
                .filter(p -> p.purpose().equals(request.purpose()))
                .findFirst()
                .orElse(null);
        if (rule == null) {
            return deny(resolution, DecisionCode.PURPOSE_MISMATCH,
                    "调用方 " + request.callerId() + " 未被授予访问用途: " + request.purpose(),
                    locatedFields, null);
        }

        List<FieldDecision> fieldDecisions = new ArrayList<>();
        DecisionCode firstDenial = null;
        String firstReason = null;

        for (LocatedField f : locatedFields) {
            // 1) 等级上限
            if (rule.maxLevels() == null || !rule.maxLevels().contains(f.level())) {
                if (firstDenial == null) {
                    firstDenial = DecisionCode.FIELD_LEVEL_EXCEEDED;
                    firstReason = "用途 " + request.purpose() + " 不允许访问等级 "
                            + f.level() + " 字段: " + f.path();
                }
                fieldDecisions.add(new FieldDecision(f.path(),
                        DecisionCode.FIELD_LEVEL_EXCEEDED, f.level(),
                        TransformType.NONE, "超出该用途允许的最高等级"));
                continue;
            }
            // 2) 显式字段授权
            FieldGrant grant = rule.grants() == null ? null : rule.grants().stream()
                    .filter(g -> g.fieldPath().equals(f.pattern())
                            || g.fieldPath().equals(f.path()))
                    .findFirst()
                    .orElse(null);
            if (grant == null) {
                if (firstDenial == null) {
                    firstDenial = DecisionCode.POLICY_MISSING;
                    firstReason = "字段缺少显式授权策略: " + f.path();
                }
                fieldDecisions.add(new FieldDecision(f.path(),
                        DecisionCode.POLICY_MISSING, f.level(),
                        TransformType.NONE, "策略中没有该字段的授权条目"));
                continue;
            }
            // 3) 用途再校验（字段级用途必须包含当前用途）
            if (grant.allowedPurposes() == null
                    || !grant.allowedPurposes().contains(request.purpose())) {
                if (firstDenial == null) {
                    firstDenial = DecisionCode.PURPOSE_MISMATCH;
                    firstReason = "字段 " + f.path() + " 的授权用途不包含: " + request.purpose();
                }
                fieldDecisions.add(new FieldDecision(f.path(),
                        DecisionCode.PURPOSE_MISMATCH, f.level(),
                        TransformType.NONE, "字段级用途不匹配"));
                continue;
            }
            TransformType transform = grant.transform() == null
                    ? TransformType.NONE : grant.transform();
            fieldDecisions.add(new FieldDecision(f.path(), DecisionCode.ALLOW,
                    f.level(), transform, "放行，处理方式 " + transform));
        }

        if (firstDenial != null) {
            return new EvaluationResult(firstDenial, firstReason, resolution,
                    List.copyOf(fieldDecisions));
        }
        return new EvaluationResult(DecisionCode.ALLOW,
                "全部敏感字段均被显式授权", resolution, List.copyOf(fieldDecisions));
    }

    private EvaluationResult deny(PolicyVersionResolution resolution, DecisionCode code,
                                  String reason, List<LocatedField> locatedFields,
                                  Object ignored) {
        List<FieldDecision> fields = locatedFields.stream()
                .map(f -> new FieldDecision(f.path(), code, f.level(),
                        TransformType.NONE, reason))
                .toList();
        return new EvaluationResult(code, reason, resolution, fields);
    }
}
