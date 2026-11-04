package com.joinsoft.flow.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 目标表写入器：按主键先 DELETE 再 INSERT，保证重复执行结果一致（幂等）。
 */
public final class Writer implements AutoCloseable {

    private static final Map<String, String[]> COLUMNS = new LinkedHashMap<String, String[]>();

    static {
        COLUMNS.put("flow_def", new String[]{
                "id", "rev", "def_name", "def_key", "category", "description", "create_time", "update_time",
                "version", "meta_info", "deployment_id", "tenant_id", "status", "bpmn_xml", "svg_xml", "deploy_time"
        });
        COLUMNS.put("flow_inst", new String[]{
                "id", "proc_inst_id", "business_id", "business_key", "task_id", "task_name", "start_user_id",
                "start_user_name", "start_depart", "start_role", "start_time", "end_time", "title", "state",
                "tenant_id", "ext_id", "def_id", "def_key", "def_name", "url", "variables", "active_tokens",
                "activities"
        });
        COLUMNS.put("flow_task", new String[]{
                "id", "inst_id", "def_id", "task_def_key", "name", "assignee", "candidate_users",
                "candidate_groups", "owner", "delegation_state", "form_key", "priority", "status", "token_id",
                "start_time", "claim_time", "end_time", "duration", "delete_reason", "parent_task_id", "tenant_id"
        });
        COLUMNS.put("flow_comment", new String[]{
                "id", "inst_id", "task_id", "task_def_key", "task_name", "comment_type", "user_id", "user_name",
                "message", "flow_state", "tenant_id", "create_time"
        });
    }

    private final Connection connection;
    private final boolean dryRun;
    private final int batchSize;
    private final Map<String, PreparedStatement> deletes = new LinkedHashMap<String, PreparedStatement>();
    private final Map<String, PreparedStatement> inserts = new LinkedHashMap<String, PreparedStatement>();
    private final Set<String> seen = new HashSet<String>();

    private int pending;
    private long written;
    private long duplicates;

    public Writer(Connection connection, boolean dryRun, int batchSize) {
        this.connection = connection;
        this.dryRun = dryRun;
        this.batchSize = batchSize;
    }

    public void write(String table, Map<String, Object> row) {
        String[] columns = COLUMNS.get(table);
        if (columns == null) {
            throw new MigrationException("未知目标表: " + table);
        }
        Object idValue = row.get("id");
        if (idValue == null || String.valueOf(idValue).trim().isEmpty()) {
            throw new MigrationException("写入 " + table + " 时缺少主键 id");
        }
        String id = String.valueOf(idValue);
        if (!seen.add(table + '\u0000' + id)) {
            duplicates++;
            return;
        }
        if (dryRun) {
            written++;
            // dry-run 没有 flush，按批次滚动清理，避免大库下去重集合无限增长
            if (written % batchSize == 0) {
                seen.clear();
            }
            return;
        }
        try {
            PreparedStatement delete = deleteStatement(table);
            delete.setString(1, id);
            delete.addBatch();

            PreparedStatement insert = insertStatement(table, columns);
            for (int i = 0; i < columns.length; i++) {
                bind(insert, i + 1, row.get(columns[i]));
            }
            insert.addBatch();
            written++;
            pending++;
            if (pending >= batchSize) {
                flush();
            }
        } catch (SQLException e) {
            throw new MigrationException("写入 " + table + " 失败, id=" + id + ": " + e.getMessage(), e);
        }
    }

    public void flush() {
        if (dryRun || pending == 0) {
            return;
        }
        try {
            for (PreparedStatement statement : deletes.values()) {
                statement.executeBatch();
            }
            for (PreparedStatement statement : inserts.values()) {
                statement.executeBatch();
            }
            pending = 0;
            // 本批已落库，后续同主键写入会先 DELETE 再 INSERT，不会触发主键冲突；
            // 及时清空去重集合，避免百万级迁移时内存随行数线性增长
            seen.clear();
        } catch (SQLException e) {
            throw new MigrationException("批量写入失败: " + e.getMessage(), e);
        }
    }

    public void commit() {
        if (dryRun) {
            return;
        }
        flush();
        try {
            connection.commit();
        } catch (SQLException e) {
            throw new MigrationException("提交事务失败: " + e.getMessage(), e);
        }
    }

    public void rollbackQuietly() {
        if (dryRun) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // ignore
        }
    }

    public long written() {
        return written;
    }

    public long duplicates() {
        return duplicates;
    }

    public void clear(String... tables) {
        if (dryRun) {
            return;
        }
        for (String table : tables) {
            if (!COLUMNS.containsKey(table)) {
                continue;
            }
            try {
                PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table);
                try {
                    statement.executeUpdate();
                } finally {
                    statement.close();
                }
            } catch (SQLException e) {
                throw new MigrationException("清空 " + table + " 失败: " + e.getMessage(), e);
            }
        }
        commit();
    }

    private PreparedStatement deleteStatement(String table) throws SQLException {
        PreparedStatement statement = deletes.get(table);
        if (statement == null) {
            statement = connection.prepareStatement("DELETE FROM " + table + " WHERE id = ?");
            deletes.put(table, statement);
        }
        return statement;
    }

    private PreparedStatement insertStatement(String table, String[] columns) throws SQLException {
        PreparedStatement statement = inserts.get(table);
        if (statement == null) {
            StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" (");
            StringBuilder values = new StringBuilder(" VALUES (");
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) {
                    sql.append(", ");
                    values.append(", ");
                }
                sql.append(columns[i]);
                values.append('?');
            }
            sql.append(')').append(values).append(')');
            statement = connection.prepareStatement(sql.toString());
            inserts.put(table, statement);
        }
        return statement;
    }

    private static void bind(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value == null) {
            // 用无类型 null：setNull(Types.VARCHAR) 在 PostgreSQL/瀚高上会让
            // bigint/int 列的 NULL 参数被当成 varchar，触发类型不匹配错误
            statement.setObject(index, null);
        } else if (value instanceof Timestamp) {
            statement.setTimestamp(index, (Timestamp) value);
        } else if (value instanceof byte[]) {
            statement.setBytes(index, (byte[]) value);
        } else if (value instanceof Long) {
            statement.setLong(index, (Long) value);
        } else if (value instanceof Integer) {
            statement.setInt(index, (Integer) value);
        } else if (value instanceof Boolean) {
            statement.setBoolean(index, (Boolean) value);
        } else if (value instanceof Double) {
            statement.setDouble(index, (Double) value);
        } else {
            statement.setString(index, String.valueOf(value));
        }
    }

    @Override
    public void close() {
        for (PreparedStatement statement : deletes.values()) {
            closeQuietly(statement);
        }
        for (PreparedStatement statement : inserts.values()) {
            closeQuietly(statement);
        }
        deletes.clear();
        inserts.clear();
    }

    private static void closeQuietly(PreparedStatement statement) {
        try {
            statement.close();
        } catch (SQLException ignored) {
            // ignore
        }
    }

    public static String[] columns(String table) {
        String[] columns = COLUMNS.get(table);
        return columns == null ? new String[0] : columns.clone();
    }
}
