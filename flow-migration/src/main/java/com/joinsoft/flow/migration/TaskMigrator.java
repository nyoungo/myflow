package com.joinsoft.flow.migration;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ACT_HI_TASKINST（+ ACT_RU_TASK）-> flow_task。
 *
 * <p>历史任务用于已办/流程图；运行中任务额外写 token_id，指向 flow_inst.active_tokens
 * 中对应的令牌，保证迁移后可以直接审批。</p>
 */
public final class TaskMigrator {

    private final Ctx ctx;

    public TaskMigrator(Ctx ctx) {
        this.ctx = ctx;
    }

    public void run() {
        if (!ctx.options.wants("task")) {
            return;
        }
        Db.Table history = ctx.source.table("act_hi_taskinst");
        Db.Table runtime = ctx.source.table("act_ru_task");
        if (history == null && runtime == null) {
            ctx.report.stage("flow_task", 0, 0, 0);
            ctx.report.warn("flow_task", "", "旧库没有任务表", "flow_task 未迁移");
            return;
        }

        long read = 0;
        long written = 0;
        long skipped = 0;

        if (history != null) {
            String lastId = null;
            while (true) {
                List<TaskRow> rows = fetchHistoryPage(history, runtime, lastId);
                if (rows.isEmpty()) {
                    break;
                }
                long[] result = processPage(rows, true);
                read += result[0];
                written += result[1];
                skipped += result[2];
                lastId = rows.get(rows.size() - 1).id;
                if (rows.size() < ctx.options.pageSize) {
                    break;
                }
            }
        }

        if (runtime != null) {
            String lastId = null;
            while (true) {
                List<TaskRow> rows = fetchRuntimeOnlyPage(history, runtime, lastId);
                if (rows.isEmpty()) {
                    break;
                }
                long[] result = processPage(rows, false);
                read += result[0];
                written += result[1];
                skipped += result[2];
                lastId = rows.get(rows.size() - 1).id;
                if (rows.size() < ctx.options.pageSize) {
                    break;
                }
            }
        }

        ctx.report.stage("flow_task", read, written, skipped);
    }

    // ------------------------------------------------------------------
    // 分页
    // ------------------------------------------------------------------

    private List<TaskRow> fetchHistoryPage(Db.Table history, Db.Table runtime, String lastId) {
        String idCol = history.col("ID_", "ID");
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append("h.").append(idCol).append(" AS id, ")
                .append(column("h", history, "PROC_INST_ID_", "inst_id")).append(", ")
                .append(column("h", history, "PROC_DEF_ID_", "def_id")).append(", ")
                .append(column("h", history, "TASK_DEF_KEY_", "task_def_key")).append(", ")
                .append(column("h", history, "NAME_", "name")).append(", ")
                .append(column("h", history, "ASSIGNEE_", "assignee")).append(", ")
                .append(column("h", history, "OWNER_", "owner")).append(", ")
                .append(column("h", history, "FORM_KEY_", "form_key")).append(", ")
                .append(column("h", history, "PRIORITY_", "priority")).append(", ")
                .append(column("h", history, "START_TIME_", "start_time")).append(", ")
                .append(column("h", history, "CLAIM_TIME_", "claim_time")).append(", ")
                .append(column("h", history, "END_TIME_", "end_time")).append(", ")
                .append(column("h", history, "DURATION_", "duration")).append(", ")
                .append(column("h", history, "DELETE_REASON_", "delete_reason")).append(", ")
                .append(column("h", history, "PARENT_TASK_ID_", "parent_task_id")).append(", ")
                .append(column("h", history, "TENANT_ID_", "tenant_id"));
        if (runtime != null && runtime.col("ID_", "ID") != null) {
            sql.append(", t.").append(runtime.col("ID_", "ID")).append(" AS ru_id, ")
                    .append(runtime.col("DELEGATION_") == null ? "NULL" : "t." + runtime.col("DELEGATION_"))
                    .append(" AS delegation");
        } else {
            sql.append(", NULL AS ru_id, NULL AS delegation");
        }
        sql.append(" FROM ").append(history.name).append(" h");
        if (runtime != null && runtime.col("ID_", "ID") != null) {
            sql.append(" LEFT JOIN ").append(runtime.name).append(" t ON t.")
                    .append(runtime.col("ID_", "ID")).append(" = h.").append(idCol);
        }
        if (lastId != null) {
            sql.append(" WHERE h.").append(idCol).append(" > ?");
        }
        sql.append(" ORDER BY h.").append(idCol).append(" LIMIT ").append(ctx.options.pageSize);
        return queryTasks(sql.toString(), lastId, true);
    }

    private List<TaskRow> fetchRuntimeOnlyPage(Db.Table history, Db.Table runtime, String lastId) {
        String idCol = runtime.col("ID_", "ID");
        if (idCol == null) {
            return new ArrayList<TaskRow>();
        }
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append("t.").append(idCol).append(" AS id, ")
                .append(column("t", runtime, "PROC_INST_ID_", "inst_id")).append(", ")
                .append(column("t", runtime, "PROC_DEF_ID_", "def_id")).append(", ")
                .append(column("t", runtime, "TASK_DEF_KEY_", "task_def_key")).append(", ")
                .append(column("t", runtime, "NAME_", "name")).append(", ")
                .append(column("t", runtime, "ASSIGNEE_", "assignee")).append(", ")
                .append(column("t", runtime, "OWNER_", "owner")).append(", ")
                .append(column("t", runtime, "FORM_KEY_", "form_key")).append(", ")
                .append(column("t", runtime, "PRIORITY_", "priority")).append(", ")
                .append(column("t", runtime, "CREATE_TIME_", "start_time")).append(", ")
                .append(column("t", runtime, "CLAIM_TIME_", "claim_time")).append(", ")
                .append("NULL AS end_time, NULL AS duration, NULL AS delete_reason, ")
                .append(column("t", runtime, "PARENT_TASK_ID_", "parent_task_id")).append(", ")
                .append(column("t", runtime, "TENANT_ID_", "tenant_id")).append(", ")
                .append(idCol).append(" AS ru_id, ")
                .append(runtime.col("DELEGATION_") == null ? "NULL" : runtime.col("DELEGATION_"))
                .append(" AS delegation")
                .append(" FROM ").append(runtime.name).append(" t");
        List<String> conditions = new ArrayList<String>();
        if (lastId != null) {
            conditions.add("t." + idCol + " > ?");
        }
        if (history != null && history.col("ID_", "ID") != null) {
            conditions.add("NOT EXISTS (SELECT 1 FROM " + history.name + " h WHERE h."
                    + history.col("ID_", "ID") + " = t." + idCol + ")");
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        sql.append(" ORDER BY t.").append(idCol).append(" LIMIT ").append(ctx.options.pageSize);
        return queryTasks(sql.toString(), lastId, false);
    }

    private List<TaskRow> queryTasks(String sql, String lastId, boolean withHistory) {
        List<TaskRow> rows = new ArrayList<TaskRow>();
        try {
            PreparedStatement statement = ctx.source.connection.prepareStatement(sql);
            try {
                if (lastId != null) {
                    statement.setString(1, lastId);
                }
                ResultSet rs = statement.executeQuery();
                try {
                    while (rs.next()) {
                        TaskRow row = new TaskRow();
                        row.id = Db.str(rs, "id");
                        row.instId = Db.str(rs, "inst_id");
                        row.defId = Db.str(rs, "def_id");
                        row.taskDefKey = Db.str(rs, "task_def_key");
                        row.name = Db.str(rs, "name");
                        row.assignee = Db.str(rs, "assignee");
                        row.owner = Db.str(rs, "owner");
                        row.formKey = Db.str(rs, "form_key");
                        row.priority = Db.integer(rs, "priority");
                        row.startTime = Db.timestamp(rs, "start_time");
                        row.claimTime = Db.timestamp(rs, "claim_time");
                        row.endTime = Db.timestamp(rs, "end_time");
                        row.duration = Db.longValue(rs, "duration");
                        row.deleteReason = Db.str(rs, "delete_reason");
                        row.parentTaskId = Db.str(rs, "parent_task_id");
                        row.tenantId = Db.str(rs, "tenant_id");
                        row.delegation = Db.str(rs, "delegation");
                        row.runtime = withHistory ? Db.str(rs, "ru_id") != null : true;
                        rows.add(row);
                    }
                } finally {
                    rs.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            throw new MigrationException("读取任务失败: " + e.getMessage(), e);
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    private long[] processPage(List<TaskRow> rows, boolean history) {
        Map<String, List<IdentityRef>> candidates = loadCandidates(rows);
        long read = 0;
        long written = 0;
        long skipped = 0;
        for (TaskRow row : rows) {
            read++;
            if (!Text.hasText(row.id) || !Text.hasText(row.instId)) {
                skipped++;
                ctx.report.warn("flow_task", row.id, "缺少任务主键或实例ID", "跳过");
                continue;
            }
            if (!ctx.instances.contains(row.instId)) {
                skipped++;
                continue;
            }
            String status;
            if (row.runtime) {
                status = "ACTIVE";
            } else if (row.endTime != null) {
                status = "COMPLETED";
            } else {
                status = "CANCELED";
            }
            // 防御：旧项目正常用 addCandidateUser 表示多人，但人工改库/其他版本
            // 可能在 ASSIGNEE_ 里写逗号分隔值，新引擎要求 assignee 单值
            List<String> assigneeIds = splitUsers(row.assignee);
            String assignee = null;
            if (assigneeIds.size() == 1) {
                assignee = assigneeIds.get(0);
            } else if (assigneeIds.size() > 1) {
                if ("ACTIVE".equals(status)) {
                    assignee = null;
                } else {
                    assignee = assigneeIds.get(0);
                    ctx.report.warn("flow_task", row.id, "历史任务存在多个办理人",
                            "新引擎任务只保留单人 assignee，已取第一个: " + row.assignee);
                }
            }
            String owner = ctx.users.resolveId(row.owner);
            List<IdentityRef> identityRefs = candidates.get(row.id);
            List<String> candidateUsers = new ArrayList<String>();
            List<String> candidateGroups = new ArrayList<String>();
            if (assigneeIds.size() > 1) {
                candidateUsers.addAll(assigneeIds);
            }
            if (identityRefs != null && "ACTIVE".equals(status)) {
                for (IdentityRef ref : identityRefs) {
                    if (!"candidate".equalsIgnoreCase(ref.type)) {
                        continue;
                    }
                    if (Text.hasText(ref.userId)) {
                        String userId = ctx.users.resolveId(ref.userId);
                        if (userId != null && !candidateUsers.contains(userId)) {
                            candidateUsers.add(userId);
                        }
                    }
                    if (Text.hasText(ref.groupId) && !candidateGroups.contains(ref.groupId)) {
                        candidateGroups.add(ref.groupId);
                    }
                }
            }
            if (assignee != null) {
                candidateUsers.remove(assignee);
            }

            Map<String, Object> target = new LinkedHashMap<String, Object>();
            target.put("id", row.id);
            target.put("inst_id", row.instId);
            target.put("def_id", Text.cut(row.defId, 64));
            target.put("task_def_key", Text.cut(row.taskDefKey, 100));
            target.put("name", Text.cut(Text.firstNonEmpty(row.name, row.taskDefKey, row.id), 200));
            target.put("assignee", Text.cut(assignee, 64));
            target.put("candidate_users", Text.cut(Text.csv(candidateUsers), 1000));
            target.put("candidate_groups", Text.cut(Text.csv(candidateGroups), 1000));
            target.put("owner", Text.cut(owner, 64));
            target.put("delegation_state", Text.cut(row.delegation, 20));
            target.put("form_key", Text.cut(row.formKey, 200));
            target.put("priority", row.priority == null ? Integer.valueOf(50) : row.priority);
            target.put("status", status);
            target.put("token_id", "ACTIVE".equals(status) ? Text.cut("T" + row.id, 64) : null);
            target.put("start_time", row.startTime);
            target.put("claim_time", row.claimTime);
            target.put("end_time", row.endTime);
            target.put("duration", duration(row));
            target.put("delete_reason", Text.cut(row.deleteReason, 500));
            target.put("parent_task_id", Text.cut(row.parentTaskId, 64));
            target.put("tenant_id", Text.cut(row.tenantId, 64));
            ctx.writer.write("flow_task", target);
            written++;
        }
        return new long[]{read, written, skipped};
    }

    private List<String> splitUsers(String raw) {
        List<String> result = new ArrayList<String>();
        if (!Text.hasText(raw)) {
            return result;
        }
        for (String part : raw.split("[,;，；]")) {
            if (!Text.hasText(part)) {
                continue;
            }
            String userId = ctx.users.resolveId(part.trim());
            if (userId != null && !result.contains(userId)) {
                result.add(userId);
            }
        }
        return result;
    }

    private Long duration(TaskRow row) {
        if (row.duration != null) {
            return row.duration;
        }
        if (row.startTime != null && row.endTime != null) {
            return row.endTime.getTime() - row.startTime.getTime();
        }
        return null;
    }

    private Map<String, List<IdentityRef>> loadCandidates(List<TaskRow> rows) {
        Map<String, List<IdentityRef>> result = new HashMap<String, List<IdentityRef>>();
        Db.Table link = ctx.source.table("act_ru_identitylink");
        if (link == null) {
            return result;
        }
        String taskCol = link.col("TASK_ID_");
        String typeCol = link.col("TYPE_");
        String userCol = link.col("USER_ID_");
        String groupCol = link.col("GROUP_ID_");
        if (taskCol == null) {
            return result;
        }
        List<String> ids = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        for (TaskRow row : rows) {
            if (row.runtime && Text.hasText(row.id) && seen.add(row.id)) {
                ids.add(row.id);
            }
        }
        if (ids.isEmpty()) {
            return result;
        }
        String sql = "SELECT " + taskCol + " AS task_id, "
                + (typeCol == null ? "NULL AS type" : typeCol + " AS type") + ", "
                + (userCol == null ? "NULL AS user_id" : userCol + " AS user_id") + ", "
                + (groupCol == null ? "NULL AS group_id" : groupCol + " AS group_id")
                + " FROM " + link.name + " WHERE " + taskCol + " IN (" + placeholders(ids.size()) + ")";
        try {
            PreparedStatement statement = ctx.source.connection.prepareStatement(sql);
            try {
                for (int i = 0; i < ids.size(); i++) {
                    statement.setString(i + 1, ids.get(i));
                }
                ResultSet rs = statement.executeQuery();
                try {
                    while (rs.next()) {
                        String taskId = Db.str(rs, "task_id");
                        if (taskId == null) {
                            continue;
                        }
                        IdentityRef ref = new IdentityRef();
                        ref.type = Db.str(rs, "type");
                        ref.userId = Db.str(rs, "user_id");
                        ref.groupId = Db.str(rs, "group_id");
                        List<IdentityRef> list = result.get(taskId);
                        if (list == null) {
                            list = new ArrayList<IdentityRef>();
                            result.put(taskId, list);
                        }
                        list.add(ref);
                    }
                } finally {
                    rs.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            ctx.report.warn("act_ru_identitylink", "", "读取候选审批人失败", e.getMessage());
        }
        return result;
    }

    private static String column(String tableAlias, Db.Table table, String name, String alias) {
        String actual = table.col(name);
        return actual == null ? "NULL AS " + alias : tableAlias + "." + actual + " AS " + alias;
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

    private static final class TaskRow {
        String id;
        String instId;
        String defId;
        String taskDefKey;
        String name;
        String assignee;
        String owner;
        String formKey;
        Integer priority;
        Timestamp startTime;
        Timestamp claimTime;
        Timestamp endTime;
        Long duration;
        String deleteReason;
        String parentTaskId;
        String tenantId;
        String delegation;
        boolean runtime;
    }

    private static final class IdentityRef {
        String type;
        String userId;
        String groupId;
    }
}
