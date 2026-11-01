package com.joinsoft.flow.migration;

import com.joinsoft.flow.migration.editor.DraftBpmnConverter;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ACT_RE_PROCDEF + ACT_RE_MODEL -> flow_def。
 *
 * <p>已发布定义直接取部署时的 BPMN XML/SVG；草稿模型的编辑器源可能是
 * BPMN XML，也可能是 Activiti 设计器 JSON。JSON 由 Activiti 自带的
 * BpmnJsonConverter + BpmnXMLConverter 转成 BPMN XML，因此未部署的草稿
 * 也能迁移。只有转换失败时才退化为部署快照，仍失败才跳过并告警。</p>
 */
public final class DefinitionMigrator {

    /** 新引擎无法正确执行的 BPMN 结构。 */
    private static final String[][] BLOCKING = {
            {"subProcess", "子流程"},
            {"adHocSubProcess", "临时子流程"},
            {"transaction", "事务子流程"},
            {"callActivity", "调用活动"},
            {"boundaryEvent", "边界事件"},
            {"intermediateCatchEvent", "中间捕获事件"},
            {"intermediateThrowEvent", "中间抛出事件"},
            {"eventBasedGateway", "事件网关"},
            {"complexGateway", "复杂网关"},
            {"multiInstanceLoopCharacteristics", "多实例"},
            {"timerEventDefinition", "定时事件"},
            {"messageEventDefinition", "消息事件"},
            {"signalEventDefinition", "信号事件"},
            {"conditionalEventDefinition", "条件事件"},
            {"errorEventDefinition", "错误事件"},
            {"escalationEventDefinition", "升级事件"},
            {"compensateEventDefinition", "补偿事件"}
    };

    /** 新引擎会当作空节点直接跳过，不阻断迁移，但需要提示。 */
    private static final String[][] WARNING_ONLY = {
            {"serviceTask", "服务任务"},
            {"scriptTask", "脚本任务"},
            {"sendTask", "发送任务"},
            {"receiveTask", "接收任务"},
            {"businessRuleTask", "规则任务"},
            {"manualTask", "手工任务"}
    };

    private final Ctx ctx;

    public DefinitionMigrator(Ctx ctx) {
        this.ctx = ctx;
    }

    public void run() {
        if (!ctx.options.wants("def")) {
            loadDefinitionsOnly();
            return;
        }
        long read = 0;
        long written = 0;
        long skipped = 0;
        Map<String, Timestamp> deployTimes = deployTimes();
        Db.Table procdef = ctx.source.requireTable("act_re_procdef");

        String idCol = required(procdef, "ID_", "ID");
        String keyCol = required(procdef, "KEY_", "KEY");
        String nameCol = required(procdef, "NAME_", "NAME");
        String versionCol = procdef.col("VERSION_");
        String sql = "SELECT "
                + idCol + " AS id, "
                + optional(procdef, "rev", "REV_") + ", "
                + optional(procdef, "def_key", keyCol) + ", "
                + optional(procdef, "name", nameCol) + ", "
                + optional(procdef, "category", "CATEGORY_") + ", "
                + optional(procdef, "description", "DESCRIPTION_") + ", "
                + optional(procdef, "version", "VERSION_") + ", "
                + optional(procdef, "deployment_id", "DEPLOYMENT_ID_") + ", "
                + optional(procdef, "tenant_id", "TENANT_ID_") + ", "
                + optional(procdef, "suspension_state", "SUSPENSION_STATE_") + ", "
                + optional(procdef, "resource_name", "RESOURCE_NAME_") + ", "
                + optional(procdef, "dgrm_resource_name", "DGRM_RESOURCE_NAME_")
                + " FROM " + procdef.name
                + " ORDER BY " + keyCol + (versionCol == null ? "" : ", " + versionCol);

        try {
            PreparedStatement statement = ctx.source.connection.prepareStatement(sql);
            try {
                ResultSet rs = statement.executeQuery();
                try {
                    while (rs.next()) {
                        read++;
                        String id = Db.str(rs, "id");
                        if (!Text.hasText(id)) {
                            skipped++;
                            continue;
                        }
                        String key = Text.firstNonEmpty(Db.str(rs, "def_key"), Text.keyFromDefinitionId(id));
                        String name = Text.firstNonEmpty(Db.str(rs, "name"), key);
                        Integer version = Db.integer(rs, "version");
                        String deploymentId = Db.str(rs, "deployment_id");
                        String tenant = Db.str(rs, "tenant_id");
                        Integer suspension = Db.integer(rs, "suspension_state");
                        String resourceName = Db.str(rs, "resource_name");
                        String dgrmName = Db.str(rs, "dgrm_resource_name");

                        byte[] bpmnBytes = resource(deploymentId, resourceName, "%bpmn%");
                        String bpmnXml = Text.bytesToString(bpmnBytes);
                        if (bpmnXml == null) {
                            bpmnXml = Text.bytesToString(resource(deploymentId, null, "%bpmn%"));
                        }
                        if (!Text.looksLikeXml(bpmnXml)) {
                            bpmnXml = null;
                        }

                        String svgXml = null;
                        byte[] svgBytes = resource(deploymentId, dgrmName, "%.svg");
                        if (svgBytes == null) {
                            svgBytes = resource(deploymentId, null, "%.svg");
                        }
                        String svgText = Text.bytesToString(svgBytes);
                        if (Text.looksLikeXml(svgText)) {
                            svgXml = svgText;
                        }

                        Timestamp deployTime = deployTimes.get(deploymentId);
                        if (deployTime == null) {
                            deployTime = new Timestamp(System.currentTimeMillis());
                        }

                        Ctx.DefInfo info = new Ctx.DefInfo();
                        info.id = id;
                        info.key = key;
                        info.name = name;
                        info.version = version == null ? 1 : version;
                        info.status = suspension != null && suspension.intValue() == 2 ? "SUSPENDED" : "PUBLISHED";
                        info.bpmnXml = bpmnXml;
                        collectFeatures(info, bpmnXml);
                        ctx.definitions.put(id, info);

                        if (bpmnXml == null) {
                            ctx.report.warn("flow_def", id, "找不到 BPMN 资源",
                                    "部署 " + deploymentId + " 中未找到 XML，流程定义已写入但无法继续流转");
                        }
                        if (info.blockingUnsupported()) {
                            ctx.report.warn("flow_def", id, "包含新引擎不支持的节点",
                                    String.join(",", info.unsupported()));
                        } else if (!info.warnings.isEmpty()) {
                            ctx.report.warn("flow_def", id, "包含新引擎会直接跳过的节点",
                                    String.join(",", info.warnings));
                        }

                        Map<String, Object> row = new LinkedHashMap<String, Object>();
                        String description = Text.cut(Db.str(rs, "description"), 500);
                        row.put("id", id);
                        row.put("rev", Db.integer(rs, "rev") == null ? 1 : Db.integer(rs, "rev"));
                        row.put("def_name", Text.cut(name, 200));
                        row.put("def_key", Text.cut(key, 100));
                        row.put("category", Text.cut(Db.str(rs, "category"), 100));
                        row.put("description", description);
                        row.put("create_time", deployTime);
                        row.put("update_time", deployTime);
                        row.put("version", info.version);
                        row.put("meta_info", metaInfo(name, description, 1));
                        row.put("deployment_id", Text.cut(deploymentId, 100));
                        row.put("tenant_id", Text.cut(tenant, 64));
                        row.put("status", info.status);
                        row.put("bpmn_xml", bpmnXml);
                        row.put("svg_xml", svgXml);
                        row.put("deploy_time", deployTime);
                        ctx.writer.write("flow_def", row);
                        written++;
                    }
                } finally {
                    rs.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            throw new MigrationException("读取 ACT_RE_PROCDEF 失败: " + e.getMessage(), e);
        }

        if (ctx.options.includeDrafts) {
            long[] drafts = migrateDrafts(deployTimes);
            read += drafts[0];
            written += drafts[1];
            skipped += drafts[2];
        }

        ctx.report.stage("flow_def", read, written, skipped);
    }

    /** --tables 不含 def 时只加载定义信息，供实例/任务阶段使用。 */
    private void loadDefinitionsOnly() {
        Db.Table procdef = ctx.source.table("act_re_procdef");
        if (procdef == null) {
            return;
        }
        String idCol = procdef.col("ID_", "ID");
        String keyCol = procdef.col("KEY_", "KEY");
        String resourceCol = procdef.col("RESOURCE_NAME_");
        String deploymentCol = procdef.col("DEPLOYMENT_ID_");
        String versionCol = procdef.col("VERSION_");
        String nameCol = procdef.col("NAME_", "NAME");
        if (idCol == null) {
            return;
        }
        StringBuilder sql = new StringBuilder("SELECT ").append(idCol).append(" AS id");
        if (keyCol != null) {
            sql.append(", ").append(keyCol).append(" AS def_key");
        }
        if (nameCol != null) {
            sql.append(", ").append(nameCol).append(" AS name");
        }
        if (versionCol != null) {
            sql.append(", ").append(versionCol).append(" AS version");
        }
        if (deploymentCol != null) {
            sql.append(", ").append(deploymentCol).append(" AS deployment_id");
        }
        if (resourceCol != null) {
            sql.append(", ").append(resourceCol).append(" AS resource_name");
        }
        sql.append(" FROM ").append(procdef.name);
        try {
            PreparedStatement statement = ctx.source.connection.prepareStatement(sql.toString());
            try {
                ResultSet rs = statement.executeQuery();
                try {
                    while (rs.next()) {
                        String id = Db.str(rs, "id");
                        if (!Text.hasText(id)) {
                            continue;
                        }
                        Ctx.DefInfo info = new Ctx.DefInfo();
                        info.id = id;
                        info.key = Text.firstNonEmpty(Db.str(rs, "def_key"), Text.keyFromDefinitionId(id));
                        info.name = Text.firstNonEmpty(Db.str(rs, "name"), info.key);
                        Integer version = Db.integer(rs, "version");
                        info.version = version == null ? 1 : version;
                        info.status = "PUBLISHED";
                        if (ctx.options.wants("inst")) {
                            info.bpmnXml = Text.bytesToString(
                                    resource(Db.str(rs, "deployment_id"), Db.str(rs, "resource_name"), "%bpmn%"));
                            if (!Text.looksLikeXml(info.bpmnXml)) {
                                info.bpmnXml = null;
                            }
                            collectFeatures(info, info.bpmnXml);
                        }
                        ctx.definitions.put(id, info);
                    }
                } finally {
                    rs.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            throw new MigrationException("读取 ACT_RE_PROCDEF 失败: " + e.getMessage(), e);
        }
    }

    private long[] migrateDrafts(Map<String, Timestamp> deployTimes) {
        long read = 0;
        long written = 0;
        long skipped = 0;
        Db.Table model = ctx.source.table("act_re_model");
        if (model == null) {
            return new long[]{0, 0, 0};
        }
        String idCol = model.col("ID_", "ID");
        if (idCol == null) {
            return new long[]{0, 0, 0};
        }
        String editorCol = model.col("EDITOR_SOURCE_VALUE_ID_");
        String deploymentCol = model.col("DEPLOYMENT_ID_");
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(idCol).append(" AS id, ")
                .append(optional(model, "rev", "REV_")).append(", ")
                .append(optional(model, "def_name", "NAME_")).append(", ")
                .append(optional(model, "def_key", "KEY_")).append(", ")
                .append(optional(model, "category", "CATEGORY_")).append(", ")
                .append(optional(model, "create_time", "CREATE_TIME_")).append(", ")
                .append(optional(model, "update_time", "LAST_UPDATE_TIME_")).append(", ")
                .append(optional(model, "tenant_id", "TENANT_ID_")).append(", ")
                .append(optional(model, "meta_info", "META_INFO_"))
                .append(deploymentCol == null ? ", NULL AS deployment_id" : ", " + deploymentCol + " AS deployment_id")
                .append(editorCol == null ? ", NULL AS editor_source" : ", " + editorCol + " AS editor_source")
                .append(" FROM ").append(model.name).append(" ORDER BY ").append(idCol);

        try {
            PreparedStatement statement = ctx.source.connection.prepareStatement(sql.toString());
            try {
                ResultSet rs = statement.executeQuery();
                try {
                    while (rs.next()) {
                        read++;
                        String id = Db.str(rs, "id");
                        if (!Text.hasText(id)) {
                            skipped++;
                            continue;
                        }
                        String key = Text.firstNonEmpty(Db.str(rs, "def_key"), Text.keyFromDefinitionId(id));
                        String oldMeta = Db.str(rs, "meta_info");
                        String name = Text.firstNonEmpty(Db.str(rs, "def_name"), metaValue(oldMeta, "name"), key);
                        String description = metaValue(oldMeta, "description");
                        String deploymentId = Db.str(rs, "deployment_id");
                        Ctx.DefInfo info = new Ctx.DefInfo();
                        info.id = id;
                        info.key = key;
                        info.name = name;
                        info.version = 0;
                        info.status = "DRAFT";

                        String editorSource = editorSource(Db.str(rs, "editor_source"));
                        String bpmnXml = null;
                        String convertError = null;
                        if (Text.looksLikeXml(editorSource)) {
                            bpmnXml = editorSource;
                        } else if (DraftBpmnConverter.looksLikeJson(editorSource)) {
                            DraftBpmnConverter.Result converted = DraftBpmnConverter.convert(editorSource);
                            if (converted.ok()) {
                                bpmnXml = converted.xml;
                            } else {
                                convertError = converted.error;
                            }
                        }
                        boolean fromSnapshot = false;
                        if (bpmnXml == null) {
                            bpmnXml = Text.bytesToString(resource(deploymentId, null, "%bpmn%"));
                            fromSnapshot = Text.looksLikeXml(bpmnXml);
                            if (!fromSnapshot) {
                                bpmnXml = null;
                            }
                        }
                        if (bpmnXml == null) {
                            skipped++;
                            ctx.report.warn("flow_def", id, "草稿无法迁移",
                                    (convertError == null
                                            ? "编辑器内容既不是 BPMN XML 也不是可识别的设计器 JSON"
                                            : "设计器 JSON 转换失败（" + convertError + "）")
                                            + "，且没有可用的部署快照，请检查旧模型或在新设计器中重建");
                            continue;
                        }
                        if (fromSnapshot) {
                            ctx.report.warn("flow_def", id, "草稿内容取自最近一次部署快照",
                                    (convertError == null
                                            ? "旧模型编辑器内容不可用"
                                            : "设计器 JSON 转换失败：" + convertError)
                                            + "，未部署的修改无法自动转换，请在新设计器中复核");
                        }
                        info.bpmnXml = bpmnXml;
                        collectFeatures(info, bpmnXml);

                        Timestamp createTime = Db.timestamp(rs, "create_time");
                        Timestamp updateTime = Db.timestamp(rs, "update_time");
                        if (createTime == null) {
                            createTime = deployTimes.get(deploymentId);
                        }
                        if (createTime == null) {
                            createTime = new Timestamp(System.currentTimeMillis());
                        }
                        if (updateTime == null) {
                            updateTime = createTime;
                        }

                        Map<String, Object> row = new LinkedHashMap<String, Object>();
                        row.put("id", id);
                        row.put("rev", Db.integer(rs, "rev") == null ? 1 : Db.integer(rs, "rev"));
                        row.put("def_name", Text.cut(name, 200));
                        row.put("def_key", Text.cut(key, 100));
                        row.put("category", Text.cut(Db.str(rs, "category"), 100));
                        row.put("description", Text.cut(description, 500));
                        row.put("create_time", createTime);
                        row.put("update_time", updateTime);
                        row.put("version", 0);
                        row.put("meta_info", metaInfo(name, description, 1));
                        row.put("deployment_id", null);
                        row.put("tenant_id", Text.cut(Db.str(rs, "tenant_id"), 64));
                        row.put("status", "DRAFT");
                        row.put("bpmn_xml", bpmnXml);
                        row.put("svg_xml", null);
                        row.put("deploy_time", null);
                        ctx.writer.write("flow_def", row);
                        written++;
                    }
                } finally {
                    rs.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            throw new MigrationException("读取 ACT_RE_MODEL 失败: " + e.getMessage(), e);
        }
        return new long[]{read, written, skipped};
    }

    /** 从旧 ACT_RE_MODEL.META_INFO_ JSON 中取字段，取不到返回 null。 */
    private String metaValue(String metaInfo, String field) {
        if (!Text.hasText(metaInfo)) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = Json.MAPPER.readTree(metaInfo);
            com.fasterxml.jackson.databind.JsonNode value = node == null ? null : node.get(field);
            return value == null || value.isNull() ? null : value.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /** 新引擎 flow_def.meta_info 的格式：{"name","description","revision"}。 */
    private String metaInfo(String name, String description, Integer revision) {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("name", name);
        meta.put("description", description);
        meta.put("revision", revision == null ? Integer.valueOf(1) : revision);
        return Text.cut(Json.toJson(meta), 2000);
    }

    private String editorSource(String editorSourceId) {
        if (!Text.hasText(editorSourceId)) {
            return null;
        }
        Db.Table bytearray = ctx.source.table("act_ge_bytearray");
        if (bytearray == null) {
            return null;
        }
        String idCol = bytearray.col("ID_", "ID");
        String bytesCol = bytearray.col("BYTES_", "BYTES");
        if (idCol == null || bytesCol == null) {
            return null;
        }
        try {
            PreparedStatement statement = ctx.source.connection.prepareStatement(
                    "SELECT " + bytesCol + " FROM " + bytearray.name + " WHERE " + idCol + " = ?");
            try {
                statement.setString(1, editorSourceId);
                ResultSet rs = statement.executeQuery();
                try {
                    if (rs.next()) {
                        return Text.bytesToString(rs.getBytes(1));
                    }
                    return null;
                } finally {
                    rs.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            throw new MigrationException("读取模型编辑器内容失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Timestamp> deployTimes() {
        Map<String, Timestamp> result = new LinkedHashMap<String, Timestamp>();
        Db.Table deployment = ctx.source.table("act_re_deployment");
        if (deployment == null) {
            return result;
        }
        String idCol = deployment.col("ID_", "ID");
        String timeCol = deployment.col("DEPLOY_TIME_", "DEPLOY_TIME");
        if (idCol == null || timeCol == null) {
            return result;
        }
        try {
            PreparedStatement statement = ctx.source.connection.prepareStatement(
                    "SELECT " + idCol + " AS id, " + timeCol + " AS deploy_time FROM " + deployment.name);
            try {
                ResultSet rs = statement.executeQuery();
                try {
                    while (rs.next()) {
                        String id = Db.str(rs, "id");
                        if (id != null) {
                            result.put(id, Db.timestamp(rs, "deploy_time"));
                        }
                    }
                } finally {
                    rs.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            ctx.report.warn("act_re_deployment", "", "读取部署时间失败", e.getMessage());
        }
        return result;
    }

    private byte[] resource(String deploymentId, String name, String likePattern) {
        if (!Text.hasText(deploymentId)) {
            return null;
        }
        Db.Table bytearray = ctx.source.table("act_ge_bytearray");
        if (bytearray == null) {
            return null;
        }
        String depCol = bytearray.col("DEPLOYMENT_ID_", "DEPLOYMENTID_");
        String nameCol = bytearray.col("NAME_", "NAME");
        String bytesCol = bytearray.col("BYTES_", "BYTES");
        if (depCol == null || bytesCol == null) {
            return null;
        }
        StringBuilder sql = new StringBuilder("SELECT ").append(bytesCol)
                .append(" FROM ").append(bytearray.name)
                .append(" WHERE ").append(depCol).append(" = ?");
        boolean byName = Text.hasText(name) && nameCol != null;
        if (byName) {
            sql.append(" AND ").append(nameCol).append(" = ?");
        } else if (likePattern != null && nameCol != null) {
            sql.append(" AND ").append(nameCol).append(" LIKE ?");
        }
        sql.append(" LIMIT 1");
        try {
            PreparedStatement statement = ctx.source.connection.prepareStatement(sql.toString());
            try {
                statement.setString(1, deploymentId);
                if (byName) {
                    statement.setString(2, name);
                } else if (likePattern != null && nameCol != null) {
                    statement.setString(2, likePattern);
                }
                ResultSet rs = statement.executeQuery();
                try {
                    return rs.next() ? rs.getBytes(1) : null;
                } finally {
                    rs.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            ctx.report.warn("act_ge_bytearray", deploymentId, "读取资源失败", e.getMessage());
            return null;
        }
    }

    private static void collectFeatures(Ctx.DefInfo info, String bpmnXml) {
        if (bpmnXml == null) {
            return;
        }
        String lower = bpmnXml.toLowerCase(Locale.ROOT);
        for (String[] item : BLOCKING) {
            if (containsTag(lower, item[0])) {
                info.blocking.add(item[1]);
            }
        }
        for (String[] item : WARNING_ONLY) {
            if (containsTag(lower, item[0])) {
                info.warnings.add(item[1]);
            }
        }
    }

    private static boolean containsTag(String lowerXml, String tag) {
        Pattern pattern = Pattern.compile("<(?:[\\w.-]+:)?"
                + Pattern.quote(tag.toLowerCase(Locale.ROOT)) + "(?=[\\s/>])");
        return pattern.matcher(lowerXml).find();
    }

    private static String required(Db.Table table, String... candidates) {
        String column = table.col(candidates);
        if (column == null) {
            throw new MigrationException("表 " + table.name + " 缺少必需列: " + String.join("/", candidates));
        }
        return column;
    }

    private static String optional(Db.Table table, String alias, String... candidates) {
        String column = table.col(candidates);
        return column == null ? "NULL AS " + alias : column + " AS " + alias;
    }
}
