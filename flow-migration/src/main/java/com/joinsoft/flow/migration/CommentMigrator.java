package com.joinsoft.flow.migration;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ACT_HI_COMMENT -> flow_comment。
 *
 * <p>Activiti 的审批意见统一存在评论表里，旧项目另外用 FLOW_STATE_ 记录了
 * “通过/退回/跳转”等标记，这里原样保留；comment_type 则按 ACTION_ 推断，
 * 保证新前端能区分普通意见和系统事件。</p>
 */
public final class CommentMigrator {

    private static final String TYPE_COMMENT = "comment";
    private static final String TYPE_BACK = "back";
    private static final String TYPE_JUMP = "jump";
    private static final String TYPE_CANCEL = "cancel";
    private static final String TYPE_TERMINAL = "terminal";
    private static final String TYPE_SYSTEM = "system";

    private final Ctx ctx;

    public CommentMigrator(Ctx ctx) {
        this.ctx = ctx;
    }

    public void run() {
        if (!ctx.options.wants("comment")) {
            return;
        }
        Db.Table comment = ctx.source.table("act_hi_comment");
        if (comment == null) {
            ctx.report.stage("flow_comment", 0, 0, 0);
            ctx.report.warn("flow_comment", "", "旧库没有 ACT_HI_COMMENT 表", "审批意见未迁移");
            return;
        }
        String idCol = comment.col("ID_", "ID");
        if (idCol == null) {
            ctx.report.stage("flow_comment", 0, 0, 0);
            ctx.report.warn("flow_comment", "", "ACT_HI_COMMENT 缺少 ID_ 列", "审批意见未迁移");
            return;
        }
        Db.Table task = ctx.source.table("act_hi_taskinst");

        long read = 0;
        long written = 0;
        long skipped = 0;
        long skippedEvents = 0;
        String lastId = null;
        while (true) {
            List<CommentRow> rows = fetch(comment, task, lastId);
            if (rows.isEmpty()) {
                break;
            }
            for (CommentRow row : rows) {
                read++;
                // Activiti 会把候选人的增删也写成 type=event 的评论（AddUserLink/DeleteGroupLink），
                // 这些不是审批意见，旧系统查询也只取 TYPE_='comment'，这里直接跳过并汇总提示
                if ("event".equalsIgnoreCase(row.type)) {
                    skipped++;
                    skippedEvents++;
                    continue;
                }
                if (!Text.hasText(row.id) || !Text.hasText(row.instId)) {
                    skipped++;
                    ctx.report.warn("flow_comment", row.id, "缺少主键或实例ID", "跳过");
                    continue;
                }
                if (!ctx.instances.contains(row.instId)) {
                    skipped++;
                    if (row.instId != null) {
                        ctx.report.warn("flow_comment", row.id, "所属流程实例未迁移", "inst_id=" + row.instId);
                    }
                    continue;
                }

                String userId = ctx.users.resolveId(row.userId);
                Map<String, Object> target = new LinkedHashMap<String, Object>();
                target.put("id", row.id);
                target.put("inst_id", row.instId);
                target.put("task_id", Text.cut(row.taskId, 64));
                target.put("task_def_key", Text.cut(row.taskDefKey, 100));
                target.put("task_name", Text.cut(row.taskName, 200));
                target.put("comment_type", commentType(row));
                target.put("user_id", Text.cut(userId, 64));
                target.put("user_name", Text.cut(ctx.users.name(userId), 100));
                target.put("message", Text.cut(Text.firstNonEmpty(row.message, row.fullMessage), 2000));
                target.put("flow_state", Text.cut(row.flowState, 10));
                // 旧意见表可能没有 TENANT_ID_，继承所属实例的租户，避免多租户下意见不可见
                target.put("tenant_id", Text.cut(Text.firstNonEmpty(row.tenantId,
                        ctx.instanceTenants.get(row.instId)), 64));
                target.put("create_time", row.time == null ? new Timestamp(System.currentTimeMillis()) : row.time);
                ctx.writer.write("flow_comment", target);
                written++;
            }
            lastId = rows.get(rows.size() - 1).id;
            if (rows.size() < ctx.options.pageSize) {
                break;
            }
        }
        ctx.report.stage("flow_comment", read, written, skipped);
        if (skippedEvents > 0) {
            ctx.report.warn("act_hi_comment", "", "跳过 Activiti 事件记录 " + skippedEvents + " 条",
                    "AddUserLink/DeleteGroupLink 等候选人变更事件不是审批意见，旧系统列表同样只展示 TYPE_='comment'");
        }
    }

    private List<CommentRow> fetch(Db.Table comment, Db.Table task, String lastId) {
        String idCol = comment.col("ID_", "ID");
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append("c.").append(idCol).append(" AS id, ")
                .append(column("c", comment, "PROC_INST_ID_", "inst_id")).append(", ")
                .append(column("c", comment, "TASK_ID_", "task_id")).append(", ")
                .append(column("c", comment, "TYPE_", "comment_type")).append(", ")
                .append(column("c", comment, "ACTION_", "comment_action")).append(", ")
                .append(column("c", comment, "MESSAGE_", "comment_message")).append(", ")
                .append(column("c", comment, "FULL_MSG_", "comment_full_message")).append(", ")
                .append(column("c", comment, "TIME_", "comment_time")).append(", ")
                .append(column("c", comment, "USER_ID_", "comment_user_id")).append(", ")
                .append(column("c", comment, "FLOW_STATE_", "comment_flow_state")).append(", ")
                .append(column("c", comment, "TENANT_ID_", "comment_tenant_id"));
        String commentTaskCol = comment.col("TASK_ID_");
        if (task != null && task.col("ID_", "ID") != null && commentTaskCol != null) {
            sql.append(", ").append(column("h", task, "TASK_DEF_KEY_", "task_def_key")).append(", ")
                    .append(column("h", task, "NAME_", "task_name"));
            sql.append(" FROM ").append(comment.name).append(" c LEFT JOIN ").append(task.name)
                    .append(" h ON h.").append(task.col("ID_", "ID")).append(" = c.").append(commentTaskCol);
        } else {
            sql.append(", NULL AS task_def_key, NULL AS task_name FROM ").append(comment.name).append(" c");
        }
        if (lastId != null) {
            sql.append(" WHERE c.").append(idCol).append(" > ?");
        }
        sql.append(" ORDER BY c.").append(idCol).append(" LIMIT ").append(ctx.options.pageSize);

        List<CommentRow> rows = new ArrayList<CommentRow>();
        try {
            PreparedStatement statement = ctx.source.connection.prepareStatement(sql.toString());
            try {
                if (lastId != null) {
                    statement.setString(1, lastId);
                }
                ResultSet rs = statement.executeQuery();
                try {
                    while (rs.next()) {
                        CommentRow row = new CommentRow();
                        row.id = Db.str(rs, "id");
                        row.instId = Db.str(rs, "inst_id");
                        row.taskId = Db.str(rs, "task_id");
                        row.type = Db.str(rs, "comment_type");
                        row.action = Db.str(rs, "comment_action");
                        row.message = Db.str(rs, "comment_message");
                        row.fullMessage = Db.str(rs, "comment_full_message");
                        row.time = Db.timestamp(rs, "comment_time");
                        row.userId = Db.str(rs, "comment_user_id");
                        row.flowState = Db.str(rs, "comment_flow_state");
                        row.tenantId = Db.str(rs, "comment_tenant_id");
                        row.taskDefKey = Db.str(rs, "task_def_key");
                        row.taskName = Db.str(rs, "task_name");
                        rows.add(row);
                    }
                } finally {
                    rs.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            ctx.report.warn("act_hi_comment", "", "读取审批意见失败", e.getMessage());
        }
        return rows;
    }

    private static String commentType(CommentRow row) {
        String action = row.action == null ? "" : row.action.toLowerCase(Locale.ROOT);
        String message = row.message == null ? "" : row.message;
        if ("event".equalsIgnoreCase(row.type)) {
            return TYPE_SYSTEM;
        }
        if (action.contains("delete")) {
            return TYPE_SYSTEM;
        }
        if (message.contains("退回") || message.contains("驳回")) {
            return TYPE_BACK;
        }
        if (message.contains("撤销")) {
            return TYPE_CANCEL;
        }
        if (message.contains("终止")) {
            return TYPE_TERMINAL;
        }
        if (action.contains("jump") || message.contains("跳转") || message.contains("转办")) {
            return TYPE_JUMP;
        }
        if (action.contains("comment")) {
            return TYPE_COMMENT;
        }
        return Text.hasText(row.message) ? TYPE_COMMENT : TYPE_SYSTEM;
    }

    private static String column(String tableAlias, Db.Table table, String name, String alias) {
        String actual = table.col(name);
        return actual == null ? "NULL AS " + alias : tableAlias + "." + actual + " AS " + alias;
    }

    private static final class CommentRow {
        String id;
        String instId;
        String taskId;
        String type;
        String action;
        String message;
        String fullMessage;
        Timestamp time;
        String userId;
        String flowState;
        String tenantId;
        String taskDefKey;
        String taskName;
    }
}

