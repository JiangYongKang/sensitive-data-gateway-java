package com.github.highcumontoa.sensitivedatagatewayjava.tests;

import com.github.highcumontoa.sensitivedatagatewayjava.model.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.model.CallerPolicy;
import com.github.highcumontoa.sensitivedatagatewayjava.model.ClassificationDefinition;
import com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode;
import com.github.highcumontoa.sensitivedatagatewayjava.model.EvaluationResult;
import com.github.highcumontoa.sensitivedatagatewayjava.model.FieldGrant;
import com.github.highcumontoa.sensitivedatagatewayjava.model.LocatedField;
import com.github.highcumontoa.sensitivedatagatewayjava.model.PolicyVersion;
import com.github.highcumontoa.sensitivedatagatewayjava.model.PurposeRule;
import com.github.highcumontoa.sensitivedatagatewayjava.model.SensitivityLevel;
import com.github.highcumontoa.sensitivedatagatewayjava.model.TransformType;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.DefaultPolicyEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PolicyEvaluatorTest {

    private final DefaultPolicyEvaluator evaluator = new DefaultPolicyEvaluator();

    private LocatedField field(String pattern, String path, SensitivityLevel level) {
        return new LocatedField(null, Map.of(), LocatedField.Container.MAP,
                path.substring(path.lastIndexOf('.') + 1), null,
                path, pattern, level, "v", false);
    }

    private PolicyVersion version(CallerPolicy... callers) {
        return new PolicyVersion("v-test", 0L, List.<ClassificationDefinition>of(), List.of(callers));
    }

    private FieldGrant grant(String pattern, String purpose, TransformType t) {
        return new FieldGrant(pattern, Set.of(purpose), t);
    }

    private AccessRequest request(String caller, String purpose) {
        return new AccessRequest(caller, purpose, null, Map.of("x", "y"));
    }

    @Test
    void unknownCallerIsUnauthorized() {
        PolicyVersion v = version(new CallerPolicy("known", false, List.of()));
        EvaluationResult r = evaluator.evaluate(request("stranger", "analytics"),
                List.of(field("user.name", "user.name", SensitivityLevel.L1)), v, "v-test");
        assertEquals(DecisionCode.UNAUTHORIZED_CALLER, r.overallCode());
        assertTrue(r.reason().contains("stranger"));
    }

    @Test
    void revokedCallerIsUnauthorizedNotPurposeMismatch() {
        CallerPolicy revoked = new CallerPolicy("svc-x", true,
                List.of(new PurposeRule("analytics",
                        Set.of(SensitivityLevel.L1), List.of())));
        EvaluationResult r = evaluator.evaluate(request("svc-x", "analytics"),
                List.of(field("user.name", "user.name", SensitivityLevel.L1)),
                version(revoked), "v-test");
        assertEquals(DecisionCode.UNAUTHORIZED_CALLER, r.overallCode());
        assertTrue(r.reason().contains("撤销"));
    }

    @Test
    void purposeMismatchDistinguishable() {
        CallerPolicy caller = new CallerPolicy("svc-x", false,
                List.of(new PurposeRule("billing",
                        Set.of(SensitivityLevel.L1), List.of())));
        EvaluationResult r = evaluator.evaluate(request("svc-x", "analytics"),
                List.of(field("user.name", "user.name", SensitivityLevel.L1)),
                version(caller), "v-test");
        assertEquals(DecisionCode.PURPOSE_MISMATCH, r.overallCode());
    }

    @Test
    void missingFieldGrantIsPolicyMissing() {
        CallerPolicy caller = new CallerPolicy("svc-x", false,
                List.of(new PurposeRule("analytics",
                        Set.of(SensitivityLevel.L1, SensitivityLevel.L2), List.of())));
        EvaluationResult r = evaluator.evaluate(request("svc-x", "analytics"),
                List.of(field("user.email", "user.email", SensitivityLevel.L2)),
                version(caller), "v-test");
        assertEquals(DecisionCode.POLICY_MISSING, r.overallCode());
        assertTrue(r.reason().contains("user.email"));
    }

    @Test
    void levelExceededPrecedesGrantCheck() {
        CallerPolicy caller = new CallerPolicy("svc-x", false,
                List.of(new PurposeRule("analytics",
                        Set.of(SensitivityLevel.L1),
                        List.of(grant("user.idCard", "analytics", TransformType.NONE)))));
        EvaluationResult r = evaluator.evaluate(request("svc-x", "analytics"),
                List.of(field("user.idCard", "user.idCard", SensitivityLevel.L4)),
                version(caller), "v-test");
        assertEquals(DecisionCode.FIELD_LEVEL_EXCEEDED, r.overallCode());
    }

    @Test
    void allowedFieldsProduceAllowWithTransforms() {
        CallerPolicy caller = new CallerPolicy("svc-x", false,
                List.of(new PurposeRule("analytics",
                        Set.of(SensitivityLevel.L1, SensitivityLevel.L2),
                        List.of(
                                grant("user.name", "analytics", TransformType.NONE),
                                grant("user.email", "analytics", TransformType.TOKENIZE)))));
        List<LocatedField> fields = List.of(
                field("user.name", "user.name", SensitivityLevel.L1),
                field("user.email", "user.email", SensitivityLevel.L2));
        EvaluationResult r = evaluator.evaluate(request("svc-x", "analytics"),
                fields, version(caller), "v-test");
        assertEquals(DecisionCode.ALLOW, r.overallCode());
        assertEquals(TransformType.NONE, r.fieldDecisions().get(0).transform());
        assertEquals(TransformType.TOKENIZE, r.fieldDecisions().get(1).transform());
    }
}
