package com.joinsoft.modules.flow.engine;

import com.joinsoft.common.util.JsonUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 流程图 SVG 生成器：优先使用 BPMN DI 坐标，缺失时按拓扑层级自动布局，并高亮已执行节点与连线。
 */
public final class SvgDiagramGenerator {

    private static final int NODE_WIDTH = 140;
    private static final int NODE_HEIGHT = 56;
    private static final int GAP_X = 40;
    private static final int GAP_Y = 80;
    private static final int PADDING = 30;

    private SvgDiagramGenerator() {
    }

    public static String generate(ProcessModel model, List<Map<String, Object>> activities) {
        Set<String> highlightedNodes = new HashSet<>();
        Set<String> highlightedFlows = new HashSet<>();
        if (activities != null) {
            for (Map<String, Object> activity : activities) {
                Object id = activity.get("id");
                if (id == null) {
                    continue;
                }
                if (Boolean.TRUE.equals(activity.get("flow"))) {
                    highlightedFlows.add(String.valueOf(id));
                } else {
                    highlightedNodes.add(String.valueOf(id));
                }
            }
        }
        return generate(model, highlightedNodes, highlightedFlows);
    }

    public static String toBase64(String svg) {
        return Base64.getEncoder().encodeToString(svg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static String generate(ProcessModel model, Set<String> highlightedNodes, Set<String> highlightedFlows) {
        if (model == null) {
            return emptySvg("流程定义不存在");
        }
        Map<String, Bounds> bounds = layout(model);
        int maxX = 0;
        int maxY = 0;
        for (Bounds box : bounds.values()) {
            maxX = Math.max(maxX, box.x + box.width);
            maxY = Math.max(maxY, box.y + box.height);
        }
        int width = Math.max(320, maxX + PADDING);
        int height = Math.max(160, maxY + PADDING);

        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width)
                .append("\" height=\"").append(height).append("\" viewBox=\"0 0 ")
                .append(width).append(' ').append(height).append("\">");
        svg.append("<defs><marker id=\"arrow\" markerWidth=\"10\" markerHeight=\"10\" refX=\"8\" refY=\"3\" ")
                .append("orient=\"auto\" markerUnits=\"strokeWidth\"><path d=\"M0,0 L0,6 L9,3 z\" fill=\"#8c8c8c\"/></marker>")
                .append("<marker id=\"arrow-hl\" markerWidth=\"10\" markerHeight=\"10\" refX=\"8\" refY=\"3\" ")
                .append("orient=\"auto\" markerUnits=\"strokeWidth\"><path d=\"M0,0 L0,6 L9,3 z\" fill=\"#52c41a\"/></marker>")
                .append("</defs>");
        svg.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>");

        for (SequenceFlowDef flow : model.getFlows().values()) {
            Bounds source = bounds.get(flow.getSourceRef());
            Bounds target = bounds.get(flow.getTargetRef());
            if (source == null || target == null || source == target) {
                continue;
            }
            boolean highlighted = highlightedFlows.contains(flow.getId());
            String stroke = highlighted ? "#52c41a" : "#8c8c8c";
            int x1 = source.x + source.width / 2;
            int y1 = source.y + source.height / 2;
            int x2 = target.x + target.width / 2;
            int y2 = target.y + target.height / 2;
            svg.append("<line x1=\"").append(x1).append("\" y1=\"").append(y1)
                    .append("\" x2=\"").append(x2).append("\" y2=\"").append(y2)
                    .append("\" stroke=\"").append(stroke).append("\" stroke-width=\"")
                    .append(highlighted ? 2 : 1).append("\" marker-end=\"url(#")
                    .append(highlighted ? "arrow-hl" : "arrow").append(")\"/>");
            if (flow.getName() != null && !flow.getName().trim().isEmpty()) {
                svg.append(text((x1 + x2) / 2, (y1 + y2) / 2 - 4, flow.getName(), "#595959", 11));
            }
        }

        for (ProcessNode node : model.getNodes().values()) {
            Bounds box = bounds.get(node.getId());
            if (box == null) {
                continue;
            }
            boolean highlighted = highlightedNodes.contains(node.getId());
            String stroke = highlighted ? "#52c41a" : "#8c8c8c";
            String fill = highlighted ? "#f6ffed" : "#ffffff";
            int strokeWidth = highlighted ? 2 : 1;
            switch (node.getType()) {
                case ProcessNode.START:
                case ProcessNode.END:
                case ProcessNode.TERMINATE_END:
                    svg.append("<ellipse cx=\"").append(box.x + box.width / 2).append("\" cy=\"")
                            .append(box.y + box.height / 2).append("\" rx=\"").append(box.width / 2)
                            .append("\" ry=\"").append(box.height / 2).append("\" fill=\"")
                            .append(fill).append("\" stroke=\"").append(stroke)
                            .append("\" stroke-width=\"").append(strokeWidth).append("\"/>");
                    break;
                case ProcessNode.EXCLUSIVE_GATEWAY:
                case ProcessNode.PARALLEL_GATEWAY:
                case ProcessNode.INCLUSIVE_GATEWAY:
                    svg.append("<polygon points=\"")
                            .append(box.x + box.width / 2).append(',').append(box.y).append(' ')
                            .append(box.x + box.width).append(',').append(box.y + box.height / 2).append(' ')
                            .append(box.x + box.width / 2).append(',').append(box.y + box.height).append(' ')
                            .append(box.x).append(',').append(box.y + box.height / 2)
                            .append("\" fill=\"").append(fill).append("\" stroke=\"").append(stroke)
                            .append("\" stroke-width=\"").append(strokeWidth).append("\"/>");
                    break;
                default:
                    svg.append("<rect x=\"").append(box.x).append("\" y=\"").append(box.y)
                            .append("\" width=\"").append(box.width).append("\" height=\"").append(box.height)
                            .append("\" rx=\"8\" ry=\"8\" fill=\"").append(fill)
                            .append("\" stroke=\"").append(stroke)
                            .append("\" stroke-width=\"").append(strokeWidth).append("\"/>");
                    break;
            }
            String label = node.getName() == null || node.getName().trim().isEmpty()
                    ? (node.getId() == null ? "" : node.getId()) : node.getName();
            svg.append(text(box.x + box.width / 2, box.y + box.height / 2 + 4, label, "#262626", 12));
        }
        svg.append("</svg>");
        return svg.toString();
    }

    private static String text(int x, int y, String content, String color, int size) {
        return "<text x=\"" + x + "\" y=\"" + y + "\" text-anchor=\"middle\" font-family=\"Microsoft YaHei, Arial\" "
                + "font-size=\"" + size + "\" fill=\"" + color + "\">" + escape(truncate(content, 16)) + "</text>";
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max - 1) + "…" : value;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String emptySvg(String message) {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"320\" height=\"120\">"
                + "<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>"
                + "<text x=\"160\" y=\"65\" text-anchor=\"middle\" font-size=\"13\" fill=\"#8c8c8c\">"
                + escape(message) + "</text></svg>";
        return svg;
    }

    /** 自动布局：按流程拓扑层级分列。 */
    private static Map<String, Bounds> layout(ProcessModel model) {
        Map<String, Bounds> result = new LinkedHashMap<>();
        Map<String, Integer> levels = new LinkedHashMap<>();
        List<String> queue = new ArrayList<>();
        if (model.getStartEventId() != null) {
            queue.add(model.getStartEventId());
            levels.put(model.getStartEventId(), 0);
        }
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 2000) {
            String current = queue.remove(0);
            int level = levels.getOrDefault(current, 0);
            for (SequenceFlowDef flow : model.outgoingOf(current)) {
                if (!levels.containsKey(flow.getTargetRef())) {
                    levels.put(flow.getTargetRef(), level + 1);
                    queue.add(flow.getTargetRef());
                }
            }
        }
        for (ProcessNode node : model.getNodes().values()) {
            levels.putIfAbsent(node.getId(), levels.isEmpty() ? 0 : 1);
        }

        Map<Integer, Integer> rows = new LinkedHashMap<>();
        for (ProcessNode node : model.getNodes().values()) {
            int level = levels.getOrDefault(node.getId(), 0);
            int row = rows.getOrDefault(level, 0);
            rows.put(level, row + 1);
            int width = node.isEnd() || ProcessNode.START.equals(node.getType()) ? 40 : NODE_WIDTH;
            int height = node.isEnd() || ProcessNode.START.equals(node.getType()) ? 40
                    : (node.getType().endsWith("Gateway") ? 48 : NODE_HEIGHT);
            Bounds bounds = new Bounds();
            bounds.x = PADDING + level * (NODE_WIDTH + GAP_X);
            bounds.y = PADDING + row * (NODE_HEIGHT + GAP_Y);
            bounds.width = width;
            bounds.height = height;
            result.put(node.getId(), bounds);
        }
        return result;
    }

    /** 将已执行活动 JSON 转成高亮节点/连线集合，供外部直接使用。 */
    public static Map<String, Set<String>> highlights(List<Map<String, Object>> activities) {
        Set<String> nodes = new HashSet<>();
        Set<String> flows = new HashSet<>();
        if (activities != null) {
            for (Map<String, Object> activity : activities) {
                Object id = activity.get("id");
                if (id == null) {
                    continue;
                }
                if (Boolean.TRUE.equals(activity.get("flow"))) {
                    flows.add(String.valueOf(id));
                } else {
                    nodes.add(String.valueOf(id));
                }
            }
        }
        Map<String, Set<String>> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("flows", flows);
        return result;
    }

    /** 从实例活动 JSON 构建高亮信息。 */
    public static Map<String, Set<String>> highlightsFromJson(String json) {
        return highlights(JsonUtils.toList(json));
    }

    private static final class Bounds {
        private int x;
        private int y;
        private int width;
        private int height;
    }
}
