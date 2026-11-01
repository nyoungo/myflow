package com.joinsoft.flow.migration.editor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.joinsoft.flow.migration.Json;
import com.joinsoft.flow.migration.Text;
import org.activiti.bpmn.converter.BpmnXMLConverter;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.bpmn.model.Process;
import org.activiti.editor.language.json.converter.BpmnJsonConverter;

import java.nio.charset.StandardCharsets;

/**
 * 把旧 Activiti 设计器保存的 JSON（ACT_RE_MODEL.EDITOR_SOURCE_VALUE_ID_ 指向的内容）
 * 转成 BPMN 2.0 XML。
 *
 * <p>Activiti 6 自带 {@link BpmnJsonConverter} 与 {@link BpmnXMLConverter}，
 * 两者与设计器 JSON 是同一套模型，因此草稿不需要在新设计器中重建。</p>
 */
public final class DraftBpmnConverter {

    private static final Object REGISTER_LOCK = new Object();
    private static volatile boolean registered;

    private DraftBpmnConverter() {
    }

    /** 编辑器内容是否像 Activiti 设计器 JSON。 */
    public static boolean looksLikeJson(String text) {
        if (!Text.hasText(text)) {
            return false;
        }
        String trimmed = stripBom(text);
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    /**
     * 转换为 BPMN XML。失败时返回带原因的 {@link Result}，不抛异常，
     * 以便迁移工具退化为「使用部署快照 / 跳过并告警」。
     */
    public static Result convert(String json) {
        if (!Text.hasText(json)) {
            return Result.failed("编辑器内容为空");
        }
        String body = stripBom(json);
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(body);
        } catch (Exception e) {
            return Result.failed("编辑器内容不是合法 JSON: " + brief(e));
        }
        if (root == null || !root.isObject()) {
            return Result.failed("编辑器 JSON 根节点不是对象");
        }
        if (root.get("childShapes") == null || !root.get("childShapes").isArray()) {
            return Result.failed("不是 Activiti 设计器模型：缺少 childShapes");
        }
        // 设计器 JSON 一定带 properties；缺失时补空对象，避免转换器 NPE。
        if (root.get("properties") == null) {
            ((ObjectNode) root).set("properties", Json.MAPPER.createObjectNode());
        }

        registerCustomConverters();
        try {
            BpmnModel model = new BpmnJsonConverter().convertToBpmnModel(root);
            if (model == null || model.getProcesses().isEmpty()) {
                return Result.failed("转换结果没有流程定义，请检查设计器 JSON 是否完整");
            }
            Process process = model.getMainProcess();
            if (process == null || !Text.hasText(process.getId())) {
                return Result.failed("转换结果缺少流程 ID");
            }
            byte[] bytes = new BpmnXMLConverter().convertToXML(model, "UTF-8");
            String xml = bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
            if (!Text.looksLikeXml(xml) || !xml.contains("<process")) {
                return Result.failed("转换结果不是有效的 BPMN XML");
            }
            return Result.ok(xml);
        } catch (Exception e) {
            return Result.failed("Activiti 设计器 JSON 转换失败: " + brief(e));
        }
    }

    /** 把旧项目的自定义 UserTask 转换器注册进 Activiti 的静态注册表。 */
    private static void registerCustomConverters() {
        if (registered) {
            return;
        }
        synchronized (REGISTER_LOCK) {
            if (registered) {
                return;
            }
            CustomUserTaskJsonConverter.fillTypes(
                    BpmnJsonConverterProperties.getConvertersToBpmnMap(),
                    BpmnJsonConverterProperties.getConvertersToJsonMap());
            registered = true;
        }
    }

    private static String stripBom(String text) {
        String trimmed = text.trim();
        while (trimmed.startsWith("\ufeff")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed;
    }

    private static String brief(Exception e) {
        String message = e.getMessage();
        if (!Text.hasText(message)) {
            return e.getClass().getSimpleName();
        }
        return Text.cut(message.replace('\r', ' ').replace('\n', ' '), 300);
    }

    /** 转换结果。 */
    public static final class Result {

        public final String xml;
        public final String error;

        private Result(String xml, String error) {
            this.xml = xml;
            this.error = error;
        }

        static Result ok(String xml) {
            return new Result(xml, null);
        }

        static Result failed(String error) {
            return new Result(null, error);
        }

        public boolean ok() {
            return xml != null;
        }
    }
}
