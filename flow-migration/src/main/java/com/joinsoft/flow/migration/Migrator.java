package com.joinsoft.flow.migration;

import java.io.PrintStream;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 迁移编排：连接两端、建表/清空、按依赖顺序执行各阶段、做目标库校验并输出报告。
 */
public final class Migrator implements AutoCloseable {

    private final Options options;
    private final Report report;

    private Db source;
    private Db target;
    private Writer writer;
    private UserResolver users;
    private Ctx ctx;

    public Migrator(Options options, PrintStream out) {
        this.options = options;
        this.report = new Report(out, options.maxWarnings);
    }

    public Report run() {
        options.validate();
        Dialect sourceDialect = Dialect.detect(options.sourceUrl, options.sourceDialect);
        Dialect targetDialect = Dialect.detect(options.targetUrl, options.targetDialect);
        report.printHeader(options, sourceDialect, targetDialect);

        source = Db.open(options.sourceUrl, options.sourceUser, options.sourcePassword,
                options.sourceDriver, options.sourceDialect, options.sourceSchema);
        target = Db.open(options.targetUrl, options.targetUser, options.targetPassword,
                options.targetDriver, options.targetDialect, options.targetSchema);
        try {
            target.connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new MigrationException("设置目标库事务模式失败: " + e.getMessage(), e);
        }

        try {
            if (options.execute() && options.createSchema) {
                createSchema(targetDialect);
            }
            writer = new Writer(target.connection, !options.execute(), options.batchSize);
            checkTargetTables();
            if (options.execute() && options.truncate) {
                truncateSelected();
            }
            users = new UserResolver(source, report, options.assigneeFormat);
            ctx = new Ctx(options, report, source, target, writer, users);

            report.info("开始迁移 ...");
            stage("流程定义", new Runnable() {
                @Override
                public void run() {
                    new DefinitionMigrator(ctx).run();
                }
            });
            stage("流程实例", new Runnable() {
                @Override
                public void run() {
                    new InstanceMigrator(ctx).run();
                }
            });
            stage("流程任务", new Runnable() {
                @Override
                public void run() {
                    new TaskMigrator(ctx).run();
                }
            });
            stage("审批意见", new Runnable() {
                @Override
                public void run() {
                    new CommentMigrator(ctx).run();
                }
            });

            writer.commit();
            if (writer.duplicates() > 0) {
                report.warn("writer", "", "存在重复主键", "同一批次内重复的行已跳过，共 " + writer.duplicates() + " 行");
            }
            if (!options.execute()) {
                report.info("");
                report.info("dry-run 结束：没有写入目标库。确认无误后加 --mode execute 正式执行。");
            } else {
                validateTargetData();
            }
            report.printCounters();
            report.printWarningSummary();
            report.writeCsv(options.reportPath);
            return report;
        } catch (RuntimeException e) {
            if (writer != null) {
                writer.rollbackQuietly();
            } else {
                rollbackQuietly(target.connection);
            }
            throw e;
        }
    }

    private void stage(String name, Runnable action) {
        report.info("");
        report.info(">>> " + name);
        action.run();
        if (writer != null) {
            writer.commit();
        }
    }

    // ------------------------------------------------------------------
    // 建表 / 清空 / 校验
    // ------------------------------------------------------------------

    private void createSchema(Dialect dialect) {
        List<String> statements = dialect.ddlStatements();
        report.info("目标库建表（" + dialect + "，共 " + statements.size() + " 条语句）");
        for (String sql : statements) {
            Statement statement = null;
            try {
                statement = target.connection.createStatement();
                statement.execute(sql);
            } catch (SQLException e) {
                if (isAlreadyExists(e)) {
                    continue;
                }
                throw new MigrationException("执行建表语句失败: " + sql + " (" + e.getMessage() + ")", e);
            } finally {
                close(statement);
            }
        }
        commit(target.connection);
    }

    private static boolean isAlreadyExists(SQLException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("already exists")
                || message.contains("duplicate key name")
                || message.contains("duplicate column")
                || message.contains("exists");
    }

    private void truncateSelected() {
        List<String> tables = new ArrayList<String>();
        if (options.wants("comment")) {
            tables.add("flow_comment");
        }
        if (options.wants("task")) {
            tables.add("flow_task");
        }
        if (options.wants("inst")) {
            tables.add("flow_inst");
        }
        if (options.wants("def")) {
            tables.add("flow_def");
        }
        report.info("清空目标表: " + tables);
        writer.clear(tables.toArray(new String[0]));
        if (tables.size() < 4) {
            report.warn("flow_*", "", "只清空了部分表", "未选中的表可能残留孤儿数据");
        }
    }

    private void checkTargetTables() {
        List<String> required = new ArrayList<String>();
        if (options.wants("def")) {
            required.add("flow_def");
        }
        if (options.wants("inst")) {
            required.add("flow_inst");
        }
        if (options.wants("task")) {
            required.add("flow_task");
        }
        if (options.wants("comment")) {
            required.add("flow_comment");
        }
        List<String> missing = new ArrayList<String>();
        for (String table : required) {
            if (target.table(table) == null) {
                missing.add(table);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        if (options.execute()) {
            throw new MigrationException("目标库缺少表: " + missing
                    + "；请先执行建表（不要加 --create-schema false）或手工导入 schema");
        }
        report.warn("flow_*", "", "目标库缺少表 " + missing,
                "dry-run 仍可统计源数据，正式执行时会自动建表");
    }

    private void validateTargetData() {
        report.info("");
        report.info(">>> 目标库校验");
        checkOrphan("flow_task", "inst_id", "flow_task 存在孤儿任务（实例未迁移）");
        checkOrphan("flow_comment", "inst_id", "flow_comment 存在孤儿意见（实例未迁移）");
        checkMissingDefinition();
        checkDuplicateBusinessKey();
        if (options.wants("task")) {
            checkRunningWithoutTask();
        }
    }

    private void checkOrphan(String table, String column, String issue) {
        Db.Table child = target.table(table);
        Db.Table parent = target.table("flow_inst");
        if (child == null || parent == null) {
            return;
        }
        String childColumn = child.col(column);
        if (childColumn == null) {
            return;
        }
        String sql = "SELECT COUNT(*) FROM " + child.name + " c LEFT JOIN " + parent.name
                + " p ON p.id = c." + childColumn + " WHERE p.id IS NULL";
        long count = count(sql);
        if (count > 0) {
            report.warn(table, "", issue, "共 " + count + " 行");
        }
    }

    private void checkMissingDefinition() {
        Db.Table inst = target.table("flow_inst");
        Db.Table def = target.table("flow_def");
        if (inst == null || def == null) {
            return;
        }
        String sql = "SELECT COUNT(*) FROM " + inst.name + " i LEFT JOIN " + def.name
                + " d ON d.id = i.def_id WHERE i.def_id IS NOT NULL AND d.id IS NULL";
        long count = count(sql);
        if (count > 0) {
            report.warn("flow_inst", "", "实例引用的流程定义不存在", "共 " + count + " 行，请检查定义迁移");
        }
    }

    private void checkDuplicateBusinessKey() {
        Db.Table inst = target.table("flow_inst");
        if (inst == null) {
            return;
        }
        String sql = "SELECT business_key, COUNT(*) AS c FROM " + inst.name
                + " WHERE business_key IS NOT NULL AND business_key <> '' GROUP BY business_key"
                + " HAVING COUNT(*) > 1";
        try {
            PreparedStatement statement = target.connection.prepareStatement(sql);
            try {
                ResultSet rs = statement.executeQuery();
                try {
                    while (rs.next()) {
                        String key = Db.str(rs, "business_key");
                        Long count = Db.longValue(rs, "c");
                        report.warn("flow_inst", key, "business_key 重复", "共 " + count + " 条");
                    }
                } finally {
                    rs.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            report.warn("flow_inst", "", "重复 business_key 校验失败", e.getMessage());
        }
    }

    private void checkRunningWithoutTask() {
        Db.Table inst = target.table("flow_inst");
        Db.Table task = target.table("flow_task");
        if (inst == null || task == null) {
            return;
        }
        String sql = "SELECT COUNT(*) FROM " + inst.name + " i WHERE i.state = '1' AND NOT EXISTS ("
                + "SELECT 1 FROM " + task.name + " t WHERE t.inst_id = i.id AND t.status = 'ACTIVE')";
        long count = count(sql);
        if (count > 0) {
            report.warn("flow_inst", "", "运行中实例没有活动任务", "共 " + count + " 条，迁移后无法继续审批，请人工处理");
        }
    }

    private long count(String sql) {
        try {
            PreparedStatement statement = target.connection.prepareStatement(sql);
            try {
                ResultSet rs = statement.executeQuery();
                try {
                    return rs.next() ? rs.getLong(1) : 0L;
                } finally {
                    rs.close();
                }
            } finally {
                statement.close();
            }
        } catch (SQLException e) {
            report.warn("校验", "", "SQL 执行失败", sql + " -> " + e.getMessage());
            return 0L;
        }
    }

    private static void commit(java.sql.Connection connection) {
        try {
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
        } catch (SQLException e) {
            throw new MigrationException("提交事务失败: " + e.getMessage(), e);
        }
    }

    private static void rollbackQuietly(java.sql.Connection connection) {
        try {
            if (connection != null && !connection.getAutoCommit()) {
                connection.rollback();
            }
        } catch (SQLException ignored) {
            // ignore
        }
    }

    private static void close(Statement statement) {
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException ignored) {
                // ignore
            }
        }
    }

    @Override
    public void close() {
        if (users != null) {
            users.close();
        }
        if (writer != null) {
            writer.close();
        }
        if (source != null) {
            source.close();
        }
        if (target != null) {
            target.close();
        }
    }
}
