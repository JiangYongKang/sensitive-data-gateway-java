package com.github.highcumontoa.sensitivedatagatewayjava.model;

import java.util.List;

/**
 * 整次请求的判定结论。
 */
public record EvaluationResult(
        DecisionCode overallCode,
        String reason,
        PolicyVersionResolution versionResolution,
        List<FieldDecision> fieldDecisions) {

    public boolean allowed() {
        return overallCode == DecisionCode.ALLOW;
    }
}
