package com.joinsoft.flow.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON 序列化（变量、活动令牌、活动轨迹）。
 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new MigrationException("生成 JSON 失败: " + e.getMessage(), e);
        }
    }

    /**
     * Activiti 里 type=json 的变量是原文 JSON，直接当字符串会让表达式失效，
     * 因此尽量解析为 JsonNode 再写入；解析失败则降级为字符串。
     */
    public static Object parseIfJson(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        char first = trimmed.charAt(0);
        if (first != '{' && first != '[' && first != '"' && first != '-' && !Character.isDigit(first)
                && !"true".equals(trimmed) && !"false".equals(trimmed) && !"null".equals(trimmed)) {
            return text;
        }
        try {
            JsonNode node = MAPPER.readTree(trimmed);
            return node == null ? text : node;
        } catch (Exception e) {
            return text;
        }
    }
}
