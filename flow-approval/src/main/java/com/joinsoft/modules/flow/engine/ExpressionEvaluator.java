package com.joinsoft.modules.flow.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 轻量表达式求值器，支持 BPMN 条件表达式的常见写法：
 * <pre>
 *   ${flow == 1}
 *   ${amount &gt; 1000 and dept == 'sales'}
 *   ${approved}
 *   ${empty(assignee)}
 * </pre>
 */
@Slf4j
@Component
public class ExpressionEvaluator {

    public boolean evaluate(String expression, Map<String, Object> variables) {
        if (expression == null || expression.trim().isEmpty()) {
            return true;
        }
        String expr = unwrap(expression.trim());
        if (expr.isEmpty()) {
            return true;
        }
        try {
            Parser parser = new Parser(expr, variables);
            Object value = parser.parseExpression();
            parser.skipWhitespace();
            if (!parser.atEnd()) {
                throw new IllegalArgumentException("无法解析的表达式片段: " + expr.substring(parser.position()));
            }
            return truthy(value);
        } catch (Exception e) {
            log.warn("条件表达式求值失败，按 false 处理。expression={}, error={}", expression, e.getMessage());
            return false;
        }
    }

    private String unwrap(String expression) {
        String value = expression.trim();
        if ((value.startsWith("${") || value.startsWith("#{")) && value.endsWith("}")) {
            value = value.substring(2, value.length() - 1).trim();
        }
        return value;
    }

    private boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0D;
        }
        String text = String.valueOf(value).trim();
        return !text.isEmpty() && !"false".equalsIgnoreCase(text) && !"0".equals(text);
    }

    /** 递归下降解析器。 */
    private static final class Parser {

        private final String source;
        private final Map<String, Object> variables;
        private int index;

        private Parser(String source, Map<String, Object> variables) {
            this.source = source;
            this.variables = variables;
        }

        private Object parseExpression() {
            return parseOr();
        }

        private Object parseOr() {
            Object left = parseAnd();
            while (true) {
                skipWhitespace();
                if (match("||") || matchWord("or")) {
                    Object right = parseAnd();
                    left = truthy(left) || truthy(right);
                } else {
                    return left;
                }
            }
        }

        private Object parseAnd() {
            Object left = parseNot();
            while (true) {
                skipWhitespace();
                if (match("&&") || matchWord("and")) {
                    Object right = parseNot();
                    left = truthy(left) && truthy(right);
                } else {
                    return left;
                }
            }
        }

        private Object parseNot() {
            skipWhitespace();
            if (match("!") && !peek("=")) {
                return !truthy(parseNot());
            }
            if (matchWord("not")) {
                return !truthy(parseNot());
            }
            return parseComparison();
        }

        private Object parseComparison() {
            Object left = parsePrimary();
            skipWhitespace();
            String operator = peekOperator();
            if (operator == null) {
                return left;
            }
            consume(operator.length());
            Object right = parsePrimary();
            switch (operator) {
                case "==":
                    return equalsValue(left, right);
                case "!=":
                    return !equalsValue(left, right);
                case ">":
                    return compare(left, right) > 0;
                case ">=":
                    return compare(left, right) >= 0;
                case "<":
                    return compare(left, right) < 0;
                case "<=":
                    return compare(left, right) <= 0;
                default:
                    throw new IllegalArgumentException("未知操作符: " + operator);
            }
        }

        private Object parsePrimary() {
            skipWhitespace();
            if (match("(")) {
                Object value = parseExpression();
                skipWhitespace();
                if (!match(")")) {
                    throw new IllegalArgumentException("缺少右括号");
                }
                return value;
            }
            if (atEnd()) {
                throw new IllegalArgumentException("表达式意外结束");
            }
            char current = source.charAt(index);
            if (current == '\'' || current == '"') {
                return readString(current);
            }
            if (Character.isDigit(current) || (current == '-' && index + 1 < source.length()
                    && Character.isDigit(source.charAt(index + 1)))) {
                return readNumber();
            }
            String word = readIdentifier();
            if (word == null) {
                throw new IllegalArgumentException("无法识别的字符: " + current);
            }
            if ("true".equalsIgnoreCase(word)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(word)) {
                return Boolean.FALSE;
            }
            if ("null".equalsIgnoreCase(word)) {
                return null;
            }
            if ("empty".equalsIgnoreCase(word) || "notEmpty".equalsIgnoreCase(word)) {
                skipWhitespace();
                if (!match("(")) {
                    throw new IllegalArgumentException("函数缺少参数: " + word);
                }
                Object argument = parseExpression();
                skipWhitespace();
                if (!match(")")) {
                    throw new IllegalArgumentException("函数缺少右括号: " + word);
                }
                boolean empty = isEmpty(argument);
                return "empty".equalsIgnoreCase(word) ? empty : !empty;
            }
            return readVariable(word);
        }

        private Object readVariable(String path) {
            if (variables == null) {
                return null;
            }
            if (variables.containsKey(path)) {
                return variables.get(path);
            }
            Object current = variables;
            for (String part : path.split("\\.")) {
                if (current instanceof Map) {
                    current = ((Map<?, ?>) current).get(part);
                } else {
                    return null;
                }
            }
            return current;
        }

        private boolean isEmpty(Object value) {
            if (value == null) {
                return true;
            }
            if (value instanceof String) {
                return ((String) value).trim().isEmpty();
            }
            if (value instanceof List) {
                return ((List<?>) value).isEmpty();
            }
            if (value instanceof Map) {
                return ((Map<?, ?>) value).isEmpty();
            }
            return false;
        }

        private boolean equalsValue(Object left, Object right) {
            if (left == null || right == null) {
                return left == right;
            }
            if (left instanceof Number && right instanceof Number) {
                return Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue()) == 0;
            }
            if (left instanceof Boolean || right instanceof Boolean) {
                return truthy(left) == truthy(right);
            }
            return String.valueOf(left).equals(String.valueOf(right));
        }

        private int compare(Object left, Object right) {
            if (left instanceof Number && right instanceof Number) {
                return Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue());
            }
            String l = left == null ? "" : String.valueOf(left);
            String r = right == null ? "" : String.valueOf(right);
            return l.compareTo(r);
        }

        private String peekOperator() {
            if (startsWith("==") || startsWith("!=") || startsWith(">=") || startsWith("<=")) {
                return source.substring(index, index + 2);
            }
            if (startsWith(">") || startsWith("<")) {
                return source.substring(index, index + 1);
            }
            return null;
        }

        private boolean startsWith(String token) {
            return source.startsWith(token, index);
        }

        private boolean match(String token) {
            if (startsWith(token)) {
                index += token.length();
                return true;
            }
            return false;
        }

        private boolean matchWord(String word) {
            if (!source.regionMatches(true, index, word, 0, word.length())) {
                return false;
            }
            int end = index + word.length();
            if (end < source.length()) {
                char next = source.charAt(end);
                if (Character.isLetterOrDigit(next) || next == '_') {
                    return false;
                }
            }
            index = end;
            return true;
        }

        private boolean peek(String token) {
            return startsWith(token);
        }

        private void consume(int length) {
            index += length;
        }

        private String readIdentifier() {
            int start = index;
            while (index < source.length()) {
                char c = source.charAt(index);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.') {
                    index++;
                } else {
                    break;
                }
            }
            return index > start ? source.substring(start, index) : null;
        }

        private Object readNumber() {
            int start = index;
            if (source.charAt(index) == '-') {
                index++;
            }
            while (index < source.length()) {
                char c = source.charAt(index);
                if (Character.isDigit(c) || c == '.') {
                    index++;
                } else {
                    break;
                }
            }
            String text = source.substring(start, index);
            return text.contains(".") ? (Object) Double.parseDouble(text) : (Object) Long.parseLong(text);
        }

        private String readString(char quote) {
            index++;
            StringBuilder builder = new StringBuilder();
            while (index < source.length()) {
                char c = source.charAt(index++);
                if (c == '\\' && index < source.length()) {
                    builder.append(source.charAt(index++));
                } else if (c == quote) {
                    return builder.toString();
                } else {
                    builder.append(c);
                }
            }
            throw new IllegalArgumentException("字符串未闭合");
        }

        private void skipWhitespace() {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
                index++;
            }
        }

        private boolean atEnd() {
            return index >= source.length();
        }

        private int position() {
            return index;
        }

        private static boolean truthy(Object value) {
            if (value == null) {
                return false;
            }
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
            if (value instanceof Number) {
                return ((Number) value).doubleValue() != 0D;
            }
            String text = String.valueOf(value).trim();
            return !text.isEmpty() && !"false".equalsIgnoreCase(text) && !"0".equals(text);
        }
    }
}
