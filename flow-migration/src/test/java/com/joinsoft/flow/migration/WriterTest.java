package com.joinsoft.flow.migration;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 写入器：同主键覆盖、批量去重集合滚动清理、NULL 绑定。
 */
class WriterTest {

    @Test
    void overwritesSamePrimaryKeyAfterFlush() throws Exception {
        Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:writer1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        connection.setAutoCommit(false);
        Statement statement = connection.createStatement();
        try {
            for (String sql : Dialect.H2.ddlStatements()) {
                statement.execute(sql);
            }
        } finally {
            statement.close();
        }
        connection.commit();

        Writer writer = new Writer(connection, false, 1);
        try {
            writer.write("flow_comment", comment("c1", "第一次"));
            writer.write("flow_comment", comment("c1", "第二次"));
            writer.commit();
            assertEquals(0, writer.duplicates(), "批次提交后同主键重写不应算作重复");
        } finally {
            writer.close();
        }

        assertEquals(1, count(connection, "flow_comment"));
        assertEquals("第二次", text(connection, "SELECT message FROM flow_comment WHERE id = 'c1'"));

        // 可空 BIGINT 列写入 null 不应触发类型问题
        Writer taskWriter = new Writer(connection, false, 10);
        try {
            Map<String, Object> task = new LinkedHashMap<String, Object>();
            task.put("id", "t1");
            task.put("inst_id", "c1");
            task.put("status", "ACTIVE");
            taskWriter.write("flow_task", task);
            taskWriter.commit();
        } finally {
            taskWriter.close();
        }
        assertEquals("ACTIVE", text(connection, "SELECT status FROM flow_task WHERE id = 't1'"));
        assertEquals(null, text(connection, "SELECT duration FROM flow_task WHERE id = 't1'"),
                "duration 应为 NULL");

        connection.close();
    }

    private static Map<String, Object> comment(String id, String message) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("inst_id", "p1");
        row.put("comment_type", "comment");
        row.put("message", message);
        return row;
    }

    private static long count(Connection connection, String from) throws Exception {
        return number(connection, "SELECT COUNT(*) FROM " + from);
    }

    private static long number(Connection connection, String sql) throws Exception {
        Statement statement = connection.createStatement();
        try {
            ResultSet rs = statement.executeQuery(sql);
            try {
                return rs.next() ? rs.getLong(1) : 0L;
            } finally {
                rs.close();
            }
        } finally {
            statement.close();
        }
    }

    private static String text(Connection connection, String sql) throws Exception {
        Statement statement = connection.createStatement();
        try {
            ResultSet rs = statement.executeQuery(sql);
            try {
                return rs.next() ? rs.getString(1) : null;
            } finally {
                rs.close();
            }
        } finally {
            statement.close();
        }
    }
}