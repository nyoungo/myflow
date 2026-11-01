package com.joinsoft.flow.migration;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ACT_HI_PROCINST + act_business_data -> flow_inst。
 *
 * <p>同时把 ACT_HI_VARINST / ACT_RU_VARIABLE 还原成 variables JSON，把
 * ACT_HI_ACTINST 还原成 activities JSON，把 ACT_RU_TASK / ACT_RU_EXECUTION
 * 还原成 active_tokens JSON，使运行中的实例可以在新引擎里继续审批。</p>
 */
public final class InstanceMigrator {

    private static final String STATE_RUNNING = "1";
    private static final String STATE_FINISHED = "2";
    private static final String STATE_TERMINATED = "3";
    private static final String STATE_REPEALED = "4";

    private final Ctx ctx;
    private final Set<String> warnedSerializable = new LinkedHashSet<String>();

    public InstanceMigrator(Ctx ctx) {
        this.ctx = ctx;
    }

    public void run() {
        if (!ctx.options.wants("inst")) {
            return;
        }
        Db.Table procinst = ctx.source.requireTable("act_hi_procinst");
        Db.Table business = ctx.source.table("act_business_data");
        if (procinst.col("ID_", "ID") == null) {
            throw new MigrationException("act_hi_procinst 缺少 ID_ 列");
        }

        long read = 0;
        long written = 0;
        long skipped = 0;

        String lastId = null;
        while (true) {
            Page page = fetchBusinessPage(business, procinst, lastId);
            if (page.rows.isEmpty()) {
                break;
            }
            long[] result = processPage(page.rows);
            read += result[0];
            written += result[1];
            skipped += result[2];
            lastId = page.lastId;
            if (page.rows.size() < ctx.options.pageSize) {
                break;
            }
        }

        lastId = null;
        while (true) {
            Page page = fetchOrphanPage(business, procinst, lastId);
            if (page.rows.isEmpty()) {
                break;
            }
            long[] result = processPage(page.rows);
            read += result[0];
            written += result[1];
            skipped += result[2];
            lastId = page.lastId;
            if (page.rows.size() < ctx.options.pageSize) {
                break;
            }
        }

        ctx.report.stage("flow_inst", read, written, skipped);
    }

    // ------------------------------------------------------------------
    // 分页读取
    // ------------------------------------------------------------------

    private Page fetchBusinessPage(Db.Table business, Db.Table procinst, String lastId) {
        if (business == null) {
            return new Page();
        }
        String sql = businessSelect(business, procinst)
                + (lastId == null ? "" : " WHERE b." + business.col("ID_", "ID") + " > ?")
                + " ORDER BY b." + business.col("ID_", "ID") + " LIMIT " + ctx.options.pageSize;
        return queryInstances(sql, lastId);
    }

    private Page fetchOrphanPage(Db.Table business, Db.Table procinst, String lastId) {
        String idCol = procinst.col("ID_", "ID");
        String businessProcCol = business == null ? null : business.col("PROC_INST_ID_");
        StringBuilder sql = new StringBuilder(procOnlySelect(procinst));
        List<String> conditions = new ArrayList<String>();
        if (lastId != null) {
            conditions.add("p." + idCol + " > ?");
        }
        if (businessProcCol != null) {
            conditions.add("NOT EXISTS (SELECT 1 FROM " + business.name + " b WHERE b." + businessProcCol
                    + " = p." + idCol + ")");
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        sql.append(" ORDER BY p.").append(idCol).append(" LIMIT ").append(ctx.options.pageSize);
        return queryInstances(sql.toString(), lastId);
    }

    private Page queryInstances(String sql, String lastId) {
        Page page = new Page();
        try {
            PreparedStatement statement = ctx.source.connection.prepareStatement(sql);
            try {
                if (lastId != null) {
                    statement.setString(1, lastId);
                }
                ResultSet rs = statement.executeQuery();
                try {
                    while (rs.next()) {
                        page.rows.add(readInstanceRow(rs));
                    }
                } finally {
                    rs.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            throw new MigrationException("读取流程实例失败: " + e.getMessage(), e);
        }
        if (!page.rows.isEmpty()) {
            page.lastId = Text.firstNonEmpty(page.rows.get(page.rows.size() - 1).bId,
                    page.rows.get(page.rows.size() - 1).pId);
        }
        return page;
    }

    private InstRow readInstanceRow(ResultSet rs) {
        InstRow row = new InstRow();
        row.bId = Db.str(rs, "b_id");
        row.bProc = Db.str(rs, "b_proc");
        row.bBusinessId = Db.str(rs, "b_business_id");
        row.bBusinessKey = Db.str(rs, "b_business_key");
        row.bTitle = Db.str(rs, "b_title");
        row.bState = Db.str(rs, "b_state");
        row.bStartUser = Db.str(rs, "b_start_user");
        row.bStartTime = Db.timestamp(rs, "b_start_time");
        row.bTenant = Db.str(rs, "b_tenant");
        row.bExt = Db.str(rs, "b_ext");
        row.pId = Db.str(rs, "p_id");
        row.pBusinessKey = Db.str(rs, "p_business_key");
        row.pDefId = Db.str(rs, "p_def_id");
        row.pStartTime = Db.timestamp(rs, "p_start_time");
        row.pEndTime = Db.timestamp(rs, "p_end_time");
        row.pStartUser = Db.str(rs, "p_start_user");
        row.pDeleteReason = Db.str(rs, "p_delete_reason");
        row.pTenant = Db.str(rs, "p_tenant");
        row.pName = Db.str(rs, "p_name");
        return row;
    }

    private String businessSelect(Db.Table b, Db.Table p) {
        StringBuilder sql = new StringBuilder("SELECT ");
        appendColumns(sql, "b", b, "ID_", "b_id");
        appendColumns(sql, "b", b, "PROC_INST_ID_", "b_proc");
        appendColumns(sql, "b", b, "BUSINESS_ID_", "b_business_id");
        appendColumns(sql, "b", b, "BUSINESS_KEY_", "b_business_key");
        appendColumns(sql, "b", b, "TITLE_", "b_title");
        appendColumns(sql, "b", b, "STATE_", "b_state");
        appendColumns(sql, "b", b, "START_USER_ID_", "b_start_user");
        appendColumns(sql, "b", b, "START_TIME_", "b_start_time");
        appendColumns(sql, "b", b, "TENANT_ID_", "b_tenant");
        appendColumns(sql, "b", b, "EXT_ID_", "b_ext");
        appendProcColumns(sql, "p", p);
        sql.append(" FROM ").append(b.name).append(" b LEFT JOIN ").append(p.name)
                .append(" p ON p.").append(p.col("ID_", "ID"))
                .append(" = b.").append(b.col("PROC_INST_ID_"));
        return sql.toString();
    }

    private String procOnlySelect(Db.Table p) {
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append("NULL AS b_id, NULL AS b_proc, NULL AS b_business_id, NULL AS b_business_key, ")
                .append("NULL AS b_title, NULL AS b_state, NULL AS b_start_user, NULL AS b_start_time, ")
                .append("NULL AS b_tenant, NULL AS b_ext");
        appendProcColumns(sql, "p", p);
        sql.append(" FROM ").append(p.name).append(" p");
        return sql.toString();
    }

    private void appendProcColumns(StringBuilder sql, String alias, Db.Table p) {
        appendColumns(sql, "p", p, "ID_", "p_id");
        appendColumns(sql, "p", p, "BUSINESS_KEY_", "p_business_key");
        appendColumns(sql, "p", p, "PROC_DEF_ID_", "p_def_id");
        appendColumns(sql, "p", p, "START_TIME_", "p_start_time");
        appendColumns(sql, "p", p, "END_TIME_", "p_end_time");
        appendColumns(sql, "p", p, "START_USER_ID_", "p_start_user");
        appendColumns(sql, "p", p, "DELETE_REASON_", "p_delete_reason");
        appendColumns(sql, "p", p, "TENANT_ID_", "p_tenant");
        appendColumns(sql, "p", p, "NAME_", "p_name");
    }

    private static void appendColumns(StringBuilder sql, String tableAlias, Db.Table table, String column, String alias) {
        String actual = table.col(column);
        if (sql.charAt(sql.length() - 1) != ' ') {
            sql.append(", ");
        }
        sql.append(actual == null ? "NULL" : tableAlias + "." + actual).append(" AS ").append(alias);
    }

    // ------------------------------------------------------------------
    // 一页数据处理
    // ------------------------------------------------------------------

    private long[] processPage(List<InstRow> rows) {
        List<String> ids = new ArrayList<String>();
        Set<String> unique = new LinkedHashSet<String>();
        for (InstRow row : rows) {
            String id = instanceId(row);
            if (id != null && unique.add(id)) {
                ids.add(id);
            }
        }
        Map<String, List<Variable>> historyVars = loadVariables("act_hi_varinst", ids, true);
        Map<String, List<Variable>> runtimeVars = loadVariables("act_ru_variable", ids, false);
        Map<String, List<Activity>> activities = loadActivities(ids);
        Map<String, List<TaskRef>> tasks = loadRuntimeTasks(ids);
        Map<String, List<ExecutionRef>> executions = loadRuntimeExecutions(ids);

        long read = 0;
        long written = 0;
        long skipped = 0;
        for (InstRow row : rows) {
            read++;
            String id = instanceId(row);
            if (id == null) {
                skipped++;
                ctx.report.warn("flow_inst", row.bId, "缺少流程实例ID", "无法确定主键，跳过");
                continue;
            }
            if (ctx.instances.contains(id)) {
                skipped++;
                continue;
            }
            String defId = row.pDefId;
            Ctx.DefInfo definition = defId == null ? null : ctx.definitions.get(defId);
            String defKey = definition != null ? definition.key : Text.keyFromDefinitionId(defId);
            String defName = definition != null ? definition.name : null;

            LinkedHashMap<String, Object> variables = buildVariables(row, historyVars.get(id), runtimeVars.get(id), id);
            String state = resolveState(row, variables);
            if (STATE_RUNNING.equals(state) && definition != null
                    && definition.blockingUnsupported() && ctx.options.skipUnsupportedInstances) {
                skipped++;
                ctx.report.warn("flow_inst", id, "跳过运行中实例",
                        "流程定义包含新引擎无法执行的节点: " + String.join(",", definition.blocking));
                continue;
            }
            if (STATE_RUNNING.equals(state) && definition != null
                    && definition.bpmnXml == null && ctx.options.skipUnsupportedInstances) {
                skipped++;
                ctx.report.warn("flow_inst", id, "跳过运行中实例",
                        "流程定义没有可用的 BPMN XML，迁移后无法继续审批: def_id=" + defId);
                continue;
            }

            List<Map<String, Object>> activityList = buildActivities(activities.get(id));
            List<TaskRef> activeTasks = tasks.get(id);
            List<ExecutionRef> activeExecutions = executions.get(id);
            List<Map<String, Object>> tokens = buildTokens(state, activeTasks, activeExecutions);

            String startUserId = ctx.users.resolveId(Text.firstNonEmpty(row.bStartUser, row.pStartUser,
                    stringValue(variables.get("startUserId"))));
            String businessKey = Text.firstNonEmpty(row.pBusinessKey, row.bBusinessKey,
                    stringValue(variables.get("businessKey")));
            String businessId = Text.firstNonEmpty(row.bBusinessId, Text.businessIdFromKey(businessKey));
            if (businessKey == null && businessId != null && defKey != null) {
                businessKey = defKey + "." + businessId;
            }

            Map<String, Object> target = new LinkedHashMap<String, Object>();
            target.put("id", id);
            target.put("proc_inst_id", id);
            target.put("business_id", Text.cut(businessId, 100));
            target.put("business_key", Text.cut(businessKey, 200));
            target.put("task_id", Text.cut(joinTaskIds(activeTasks), 500));
            target.put("task_name", Text.cut(joinTaskNames(activeTasks), 500));
            target.put("start_user_id", Text.cut(startUserId, 64));
            target.put("start_user_name", Text.cut(ctx.users.name(startUserId), 100));
            target.put("start_depart", Text.cut(stringValue(variables.get("startDepart")), 64));
            target.put("start_role", Text.cut(stringValue(variables.get("startRole")), 500));
            target.put("start_time", firstTime(row.bStartTime, row.pStartTime));
            target.put("end_time", row.pEndTime);
            target.put("title", Text.cut(Text.firstNonEmpty(row.bTitle, stringValue(variables.get("title")),
                    row.pName, businessKey), 500));
            target.put("state", state);
            target.put("tenant_id", Text.cut(Text.firstNonEmpty(row.bTenant, row.pTenant), 64));
            target.put("ext_id", Text.cut(row.bExt, 100));
            target.put("def_id", Text.cut(defId, 64));
            target.put("def_key", Text.cut(defKey, 100));
            target.put("def_name", Text.cut(defName, 200));
            target.put("url", Text.cut(stringValue(variables.get("url")), 500));
            target.put("variables", Json.toJson(variables));
            target.put("active_tokens", Json.toJson(tokens));
            target.put("activities", Json.toJson(activityList));

            ctx.writer.write("flow_inst", target);
            ctx.instances.add(id);
            written++;

            if (STATE_RUNNING.equals(state) && tokens.isEmpty()) {
                ctx.report.warn("flow_inst", id, "运行中实例没有活动令牌", "该实例在新系统中无法继续审批，请人工检查");
            }
            if (STATE_RUNNING.equals(state) && definition == null) {
                ctx.report.warn("flow_inst", id, "找不到流程定义", "def_id=" + defId);
            }
        }
        return new long[]{read, written, skipped};
    }

    private String instanceId(InstRow row) {
        return Text.firstNonEmpty(row.pId, row.bProc, row.bId);
    }

    private String resolveState(InstRow row, Map<String, Object> variables) {
        String state = row.bState == null ? null : row.bState.trim();
        boolean running = STATE_RUNNING.equals(state);
        boolean ended = STATE_FINISHED.equals(state) || STATE_TERMINATED.equals(state) || STATE_REPEALED.equals(state);
        if (running) {
            if (row.pEndTime == null) {
                return STATE_RUNNING;
            }
            // 旧 terminal() 会结束流程却仍把 act_business_data.STATE_ 写成 1，
            // 唯一可靠信号是流程变量 flow 被置为 0
            String byReason = stateFromDeleteReason(row.pDeleteReason);
            if (STATE_REPEALED.equals(byReason)) {
                return STATE_REPEALED;
            }
            return flowZero(variables) ? STATE_TERMINATED : byReason;
        }
        if (ended) {
            return state;
        }
        if (row.pEndTime == null) {
            return STATE_RUNNING;
        }
        String byReason = stateFromDeleteReason(row.pDeleteReason);
        if (!STATE_FINISHED.equals(byReason)) {
            return byReason;
        }
        return flowZero(variables) ? STATE_TERMINATED : STATE_FINISHED;
    }

    private static boolean flowZero(Map<String, Object> variables) {
        Object flow = variables == null ? null : variables.get("flow");
        if (flow == null) {
            return false;
        }
        if (flow instanceof Number) {
            return ((Number) flow).longValue() == 0L;
        }
        String text = String.valueOf(flow).trim();
        return "0".equals(text) || "false".equalsIgnoreCase(text);
    }

    private String stateFromDeleteReason(String reason) {
        if (!Text.hasText(reason)) {
            return STATE_FINISHED;
        }
        String lower = reason.toLowerCase(Locale.ROOT);
        if (reason.contains("撤销") || lower.contains("repeal") || lower.contains("cancel")) {
            return STATE_REPEALED;
        }
        if (reason.contains("终止") || lower.contains("terminate")) {
            return STATE_TERMINATED;
        }
        return STATE_FINISHED;
    }

    private LinkedHashMap<String, Object> buildVariables(InstRow row, List<Variable> history,
                                                        List<Variable> runtime, String instanceId) {
        LinkedHashMap<String, Object> variables = new LinkedHashMap<String, Object>();
        if (history != null) {
            for (Variable variable : history) {
                variable.apply(variables);
            }
        }
        if (runtime != null) {
            for (Variable variable : runtime) {
                variable.apply(variables);
            }
        }
        if (!variables.containsKey("title") && Text.hasText(row.bTitle)) {
            variables.put("title", row.bTitle);
        }
        String startUserId = Text.firstNonEmpty(row.bStartUser, row.pStartUser,
                stringValue(variables.get("startUserId")));
        if (startUserId != null) {
            String resolved = ctx.users.resolveId(startUserId);
            if (resolved != null) {
                startUserId = resolved;
            }
            if (!variables.containsKey("startUserId")) {
                variables.put("startUserId", startUserId);
            }
            String name = ctx.users.name(startUserId);
            if (name == null) {
                // 旧引擎的 startUserName 存的是 username，不是姓名
                String legacy = stringValue(variables.get("startUserName"));
                if (Text.hasText(legacy)) {
                    name = ctx.users.name(ctx.users.resolveId(legacy));
                }
            }
            if (name != null) {
                // 新引擎要求 startUserName 是 realname，覆盖旧 username
                variables.put("startUserName", name);
            }
        }
        return variables;
    }

    private List<Map<String, Object>> buildActivities(List<Activity> list) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (list == null) {
            return result;
        }
        for (Activity activity : list) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", activity.id);
            item.put("name", Text.firstNonEmpty(activity.name, activity.id));
            item.put("type", activity.type == null ? "other" : activity.type);
            item.put("flow", "sequenceFlow".equalsIgnoreCase(activity.type));
            item.put("time", activity.time == null ? 0L : activity.time.getTime());
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> buildTokens(String state, List<TaskRef> tasks, List<ExecutionRef> executions) {
        List<Map<String, Object>> tokens = new ArrayList<Map<String, Object>>();
        if (!STATE_RUNNING.equals(state)) {
            return tokens;
        }
        Set<String> taskNodes = new LinkedHashSet<String>();
        Set<String> seen = new LinkedHashSet<String>();
        if (tasks != null) {
            for (TaskRef task : tasks) {
                if (!Text.hasText(task.id) || !Text.hasText(task.defKey)) {
                    continue;
                }
                taskNodes.add(task.defKey);
                Map<String, Object> token = new LinkedHashMap<String, Object>();
                token.put("id", Text.cut("T" + task.id, 64));
                token.put("node", task.defKey);
                if (seen.add(String.valueOf(token.get("id")))) {
                    tokens.add(token);
                }
            }
        }
        if (executions != null) {
            for (ExecutionRef execution : executions) {
                if (!execution.active || !Text.hasText(execution.node) || !Text.hasText(execution.id)) {
                    continue;
                }
                if (taskNodes.contains(execution.node)) {
                    continue;
                }
                Map<String, Object> token = new LinkedHashMap<String, Object>();
                token.put("id", Text.cut("E" + execution.id, 64));
                token.put("node", execution.node);
                if (seen.add(String.valueOf(token.get("id")))) {
                    tokens.add(token);
                }
            }
        }
        return tokens;
    }

    private String joinTaskIds(List<TaskRef> tasks) {
        if (tasks == null) {
            return "";
        }
        List<String> values = new ArrayList<String>();
        for (TaskRef task : tasks) {
            if (Text.hasText(task.id)) {
                values.add(task.id);
            }
        }
        return String.join(",", values);
    }

    private String joinTaskNames(List<TaskRef> tasks) {
        if (tasks == null) {
            return "";
        }
        List<String> values = new ArrayList<String>();
        for (TaskRef task : tasks) {
            values.add(Text.firstNonEmpty(task.name, task.defKey, ""));
        }
        return values.isEmpty() ? "" : String.join(",", values);
    }

    // ------------------------------------------------------------------
    // 关联数据加载
    // ------------------------------------------------------------------

    private Map<String, List<Variable>> loadVariables(String tableName, List<String> ids, boolean history) {
        Map<String, List<Variable>> result = new HashMap<String, List<Variable>>();
        Db.Table table = ctx.source.table(tableName);
        if (table == null || ids.isEmpty()) {
            return result;
        }
        String procCol = table.col("PROC_INST_ID_");
        String nameCol = table.col("NAME_");
        // Activiti 6 历史变量表的类型列是 VAR_TYPE_，运行时变量表才是 TYPE_
        String typeCol = table.col("TYPE_", "VAR_TYPE_");
        String longCol = table.col("LONG_");
        String doubleCol = table.col("DOUBLE_");
        String textCol = table.col("TEXT_");
        String text2Col = table.col("TEXT2_");
        String taskCol = table.col("TASK_ID_");
        if (procCol == null || nameCol == null || typeCol == null) {
            ctx.report.warn(tableName, "", "缺少流程变量类型列",
                    "未找到 TYPE_/VAR_TYPE_ 列，该表的流程变量不会迁移");
            return result;
        }
        StringBuilder sql = new StringBuilder("SELECT ").append(procCol).append(" AS pid, ")
                .append(nameCol).append(" AS name, ").append(typeCol).append(" AS type, ")
                .append(longCol == null ? "NULL AS long_val" : longCol + " AS long_val").append(", ")
                .append(doubleCol == null ? "NULL AS double_val" : doubleCol + " AS double_val").append(", ")
                .append(textCol == null ? "NULL AS text_val" : textCol + " AS text_val").append(", ")
                .append(text2Col == null ? "NULL AS text2_val" : text2Col + " AS text2_val").append(", ")
                .append(taskCol == null ? "NULL AS task_id" : taskCol + " AS task_id")
                .append(" FROM ").append(table.name)
                .append(" WHERE ").append(procCol).append(" IN (").append(placeholders(ids.size())).append(")");
        if (history) {
            String updated = table.col("LAST_UPDATED_TIME_");
            sql.append(" ORDER BY ").append(procCol);
            if (updated != null) {
                sql.append(", ").append(updated);
            } else {
                String name = table.col("NAME_");
                sql.append(", ").append(name);
            }
        }
        try {
            PreparedStatement statement = ctx.source.connection.prepareStatement(sql.toString());
            try {
                for (int i = 0; i < ids.size(); i++) {
                    statement.setString(i + 1, ids.get(i));
                }
                ResultSet rs = statement.executeQuery();
                try {
                    while (rs.next()) {
                        String pid = Db.str(rs, "pid");
                        if (pid == null) {
                            continue;
                        }
                        Variable variable = Variable.from(rs);
                        if (variable == null) {
                            String type = Db.str(rs, "type");
                            if (("serializable".equalsIgnoreCase(type) || "bytes".equalsIgnoreCase(type))
                                    && warnedSerializable.add(tableName + ":" + pid)) {
                                ctx.report.warn(tableName, pid, "存在不可迁移的变量类型",
                                        "serializable/bytes 变量已跳过，相关条件可能在审批时失效");
                            }
                            continue;
                        }
                        List<Variable> list = result.get(pid);
                        if (list == null) {
                            list = new ArrayList<Variable>();
                            result.put(pid, list);
                        }
                        list.add(variable);
                    }
                } finally {
                    rs.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            ctx.report.warn(tableName, "", "读取流程变量失败", e.getMessage());
        }
        return result;
    }

    private Map<String, List<Activity>> loadActivities(List<String> ids) {
        Map<String, List<Activity>> result = new HashMap<String, List<Activity>>();
        Db.Table table = ctx.source.table("act_hi_actinst");
        if (table == null || ids.isEmpty()) {
            if (table == null) {
                ctx.report.warn("act_hi_actinst", "", "缺少历史活动表", "流程图历史高亮将为空（不影响审批）");
            }
            return result;
        }
        String procCol = table.col("PROC_INST_ID_");
        String idCol = table.col("ACT_ID_", "ACTIVITY_ID_");
        String nameCol = table.col("ACT_NAME_", "NAME_");
        String typeCol = table.col("ACT_TYPE_", "TYPE_");
        String startCol = table.col("START_TIME_");
        if (procCol == null || idCol == null) {
            return result;
        }
        StringBuilder sql = new StringBuilder("SELECT ").append(procCol).append(" AS pid, ")
                .append(idCol).append(" AS id, ")
                .append(nameCol == null ? "NULL AS name" : nameCol + " AS name").append(", ")
                .append(typeCol == null ? "NULL AS type" : typeCol + " AS type").append(", ")
                .append(startCol == null ? "NULL AS start_time" : startCol + " AS start_time")
                .append(" FROM ").append(table.name)
                .append(" WHERE ").append(procCol).append(" IN (").append(placeholders(ids.size())).append(")");
        if (startCol != null) {
            sql.append(" ORDER BY ").append(procCol).append(", ").append(startCol);
        }
        try {
            PreparedStatement statement = ctx.source.connection.prepareStatement(sql.toString());
            try {
                for (int i = 0; i < ids.size(); i++) {
                    statement.setString(i + 1, ids.get(i));
                }
                ResultSet rs = statement.executeQuery();
                try {
                    while (rs.next()) {
                        String pid = Db.str(rs, "pid");
                        String id = Db.str(rs, "id");
                        if (pid == null || id == null) {
                            continue;
                        }
                        Activity activity = new Activity();
                        activity.id = id;
                        activity.name = Db.str(rs, "name");
                        activity.type = Db.str(rs, "type");
                        activity.time = Db.timestamp(rs, "start_time");
                        List<Activity> list = result.get(pid);
                        if (list == null) {
                            list = new ArrayList<Activity>();
                            result.put(pid, list);
                        }
                        list.add(activity);
                    }
                } finally {
                    rs.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            ctx.report.warn("act_hi_actinst", "", "读取历史活动失败", e.getMessage());
        }
        return result;
    }

    private Map<String, List<TaskRef>> loadRuntimeTasks(List<String> ids) {
        Map<String, List<TaskRef>> result = new HashMap<String, List<TaskRef>>();
        Db.Table table = ctx.source.table("act_ru_task");
        if (table == null || ids.isEmpty()) {
            return result;
        }
        String procCol = table.col("PROC_INST_ID_");
        String idCol = table.col("ID_", "ID");
        String keyCol = table.col("TASK_DEF_KEY_");
        String nameCol = table.col("NAME_");
        if (procCol == null || idCol == null) {
            return result;
        }
        String sql = "SELECT " + procCol + " AS pid, " + idCol + " AS id, "
                + (keyCol == null ? "NULL AS def_key" : keyCol + " AS def_key") + ", "
                + (nameCol == null ? "NULL AS name" : nameCol + " AS name")
                + " FROM " + table.name + " WHERE " + procCol + " IN (" + placeholders(ids.size()) + ")";
        try {
            PreparedStatement statement = ctx.source.connection.prepareStatement(sql);
            try {
                for (int i = 0; i < ids.size(); i++) {
                    statement.setString(i + 1, ids.get(i));
                }
                ResultSet rs = statement.executeQuery();
                try {
                    while (rs.next()) {
                        String pid = Db.str(rs, "pid");
                        if (pid == null) {
                            continue;
                        }
                        TaskRef task = new TaskRef();
                        task.id = Db.str(rs, "id");
                        task.defKey = Db.str(rs, "def_key");
                        task.name = Db.str(rs, "name");
                        List<TaskRef> list = result.get(pid);
                        if (list == null) {
                            list = new ArrayList<TaskRef>();
                            result.put(pid, list);
                        }
                        list.add(task);
                    }
                } finally {
                    rs.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            ctx.report.warn("act_ru_task", "", "读取运行中任务失败", e.getMessage());
        }
        return result;
    }

    private Map<String, List<ExecutionRef>> loadRuntimeExecutions(List<String> ids) {
        Map<String, List<ExecutionRef>> result = new HashMap<String, List<ExecutionRef>>();
        Db.Table table = ctx.source.table("act_ru_execution");
        if (table == null || ids.isEmpty()) {
            return result;
        }
        String procCol = table.col("PROC_INST_ID_");
        String idCol = table.col("ID_", "ID");
        String nodeCol = table.col("ACT_ID_", "ACTIVITY_ID_");
        String activeCol = table.col("IS_ACTIVE_");
        if (procCol == null || idCol == null || nodeCol == null) {
            return result;
        }
        String sql = "SELECT " + procCol + " AS pid, " + idCol + " AS id, " + nodeCol + " AS node, "
                + (activeCol == null ? "NULL AS active" : activeCol + " AS active")
                + " FROM " + table.name + " WHERE " + procCol + " IN (" + placeholders(ids.size()) + ")";
        try {
            PreparedStatement statement = ctx.source.connection.prepareStatement(sql);
            try {
                for (int i = 0; i < ids.size(); i++) {
                    statement.setString(i + 1, ids.get(i));
                }
                ResultSet rs = statement.executeQuery();
                try {
                    while (rs.next()) {
                        String pid = Db.str(rs, "pid");
                        if (pid == null) {
                            continue;
                        }
                        ExecutionRef execution = new ExecutionRef();
                        execution.id = Db.str(rs, "id");
                        execution.node = Db.str(rs, "node");
                        Object active = null;
                        try {
                            active = rs.getObject("active");
                        } catch (SQLException ignored) {
                            // ignore
                        }
                        execution.active = activeCol == null || Db.truthy(active);
                        List<ExecutionRef> list = result.get(pid);
                        if (list == null) {
                            list = new ArrayList<ExecutionRef>();
                            result.put(pid, list);
                        }
                        list.add(execution);
                    }
                } finally {
                    rs.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            ctx.report.warn("act_ru_execution", "", "读取运行中执行流失败", e.getMessage());
        }
        return result;
    }

    private static String placeholders(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('?');
        }
        return builder.toString();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Timestamp firstTime(Timestamp first, Timestamp second) {
        return first != null ? first : second;
    }

    // ------------------------------------------------------------------
    // 小结构
    // ------------------------------------------------------------------

    private static final class Page {
        final List<InstRow> rows = new ArrayList<InstRow>();
        String lastId;
    }

    private static final class InstRow {
        String bId;
        String bProc;
        String bBusinessId;
        String bBusinessKey;
        String bTitle;
        String bState;
        String bStartUser;
        Timestamp bStartTime;
        String bTenant;
        String bExt;
        String pId;
        String pBusinessKey;
        String pDefId;
        Timestamp pStartTime;
        Timestamp pEndTime;
        String pStartUser;
        String pDeleteReason;
        String pTenant;
        String pName;
    }

    private static final class Activity {
        String id;
        String name;
        String type;
        Timestamp time;
    }

    private static final class TaskRef {
        String id;
        String defKey;
        String name;
    }

    private static final class ExecutionRef {
        String id;
        String node;
        boolean active;
    }

    private static final class Variable {
        String name;
        String type;
        Long longValue;
        Double doubleValue;
        String text;
        String text2;
        boolean taskLocal;

        static Variable from(ResultSet rs) {
            Variable variable = new Variable();
            variable.name = Db.str(rs, "name");
            variable.type = Db.str(rs, "type");
            if (variable.name == null) {
                return null;
            }
            String type = variable.type == null ? "" : variable.type.toLowerCase(Locale.ROOT);
            if ("serializable".equals(type) || "bytes".equals(type)) {
                return null;
            }
            variable.longValue = Db.longValue(rs, "long_val");
            variable.doubleValue = Db.doubleValue(rs, "double_val");
            variable.text = Db.str(rs, "text_val");
            variable.text2 = Db.str(rs, "text2_val");
            variable.taskLocal = Text.hasText(Db.str(rs, "task_id"));
            return variable;
        }

        void apply(Map<String, Object> variables) {
            Object value = value();
            if (taskLocal) {
                variables.putIfAbsent(name, value);
            } else if (value != null || isExplicitNull()) {
                variables.put(name, value);
            }
        }

        private boolean isExplicitNull() {
            return "null".equalsIgnoreCase(type);
        }

        private Object value() {
            String type = this.type == null ? "" : this.type.toLowerCase(Locale.ROOT);
            switch (type) {
                case "long":
                case "integer":
                case "short":
                case "byte":
                    return longValue;
                case "double":
                case "float":
                    return doubleValue;
                case "boolean":
                    return longValue != null ? Boolean.valueOf(longValue.longValue() != 0L) : null;
                case "date":
                    // Activiti 6 的 Date 变量存 LONG_（毫秒），新引擎按毫秒时间戳处理
                    return longValue;
                case "json":
                    return Json.parseIfJson(text != null ? text : text2);
                case "bigdecimal":
                    if (text != null || text2 != null) {
                        try {
                            return new java.math.BigDecimal(text != null ? text : text2);
                        } catch (NumberFormatException e) {
                            return text != null ? text : text2;
                        }
                    }
                    return doubleValue;
                case "null":
                    return null;
                default:
                    if (text != null) {
                        return text;
                    }
                    if (text2 != null) {
                        return text2;
                    }
                    if (longValue != null) {
                        return longValue;
                    }
                    if (doubleValue != null) {
                        return doubleValue;
                    }
                    return null;
            }
        }
    }

}
