package com.joinsoft.modules.flow.engine;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 条件表达式求值器单元测试。
 */
class ExpressionEvaluatorTest {

    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();

    @Test
    void evaluatesNumericComparisons() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", 2600);
        assertThat(evaluator.evaluate("${amount > 1000}", vars)).isTrue();
        assertThat(evaluator.evaluate("${amount <= 1000}", vars)).isFalse();
        assertThat(evaluator.evaluate("${amount == 2600}", vars)).isTrue();
    }

    @Test
    void evaluatesBooleanOperators() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("dept", "sales");
        vars.put("amount", 500);
        assertThat(evaluator.evaluate("${dept == 'sales' and amount >= 100}", vars)).isTrue();
        assertThat(evaluator.evaluate("${dept == 'hr' or amount > 100}", vars)).isTrue();
        assertThat(evaluator.evaluate("${dept != 'hr' && !(amount < 100)}", vars)).isTrue();
        assertThat(evaluator.evaluate("${not approved}", Collections.singletonMap("approved", false))).isTrue();
    }

    @Test
    void evaluatesEmptyAndTruthiness() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("assignee", "");
        vars.put("flag", 1);
        assertThat(evaluator.evaluate("${empty(assignee)}", vars)).isTrue();
        assertThat(evaluator.evaluate("${notEmpty(flag)}", vars)).isTrue();
        assertThat(evaluator.evaluate("${flag}", vars)).isTrue();
        assertThat(evaluator.evaluate("${missing}", vars)).isFalse();
    }

    @Test
    void blankExpressionMeansPass() {
        assertThat(evaluator.evaluate(null, Collections.emptyMap())).isTrue();
        assertThat(evaluator.evaluate("   ", Collections.emptyMap())).isTrue();
    }

    @Test
    void invalidExpressionFallsBackToFalse() {
        assertThat(evaluator.evaluate("${amount > }", Collections.singletonMap("amount", 1))).isFalse();
    }
}
