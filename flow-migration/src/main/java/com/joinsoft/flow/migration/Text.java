package com.joinsoft.flow.migration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 字符串/集合小工具。
 */
public final class Text {

    private Text() {
    }

    public static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public static List<String> splitCsv(String value) {
        List<String> result = new ArrayList<String>();
        if (!hasText(value)) {
            return result;
        }
        for (String item : value.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /** 与 flow-approval 的 FlowTask.candidateUsers 格式一致：,a,b, */
    public static String csv(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        Set<String> distinct = new LinkedHashSet<String>();
        for (String value : values) {
            if (hasText(value)) {
                distinct.add(value.trim());
            }
        }
        if (distinct.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder(",");
        boolean first = true;
        for (String value : distinct) {
            if (!first) {
                builder.append(',');
            }
            builder.append(value);
            first = false;
        }
        return builder.append(',').toString();
    }

    public static String bytesToString(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    public static boolean looksLikeXml(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("\ufeff")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed.startsWith("<");
    }

    public static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    /** 从 Activiti 的 procDefId（key:version:uuid）里取 key。 */
    public static String keyFromDefinitionId(String definitionId) {
        if (!hasText(definitionId)) {
            return null;
        }
        int index = definitionId.indexOf(':');
        return index > 0 ? definitionId.substring(0, index) : definitionId;
    }

    public static String businessIdFromKey(String businessKey) {
        if (!hasText(businessKey)) {
            return null;
        }
        int index = businessKey.indexOf('.');
        return index >= 0 && index + 1 < businessKey.length() ? businessKey.substring(index + 1) : businessKey;
    }
}
