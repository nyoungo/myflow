package com.joinsoft.flow.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * 一个数据库连接 + 基于 DatabaseMetaData 的大小写无关表/列解析。
 *
 * <p>Activiti 在 MySQL 上通常建大写表名（ACT_RE_PROCDEF），在 PostgreSQL/瀚高上
 * 由 Activiti 自动建表时通常是全小写（act_re_procdef），因此所有表名列名都从
 * 元数据读取实际值，而不是硬编码大小写。</p>
 */
public final class Db implements AutoCloseable {

    public final Connection connection;
    public final Dialect dialect;
    private String catalog;
    private String schema;
    private final Map<String, Table> tableCache = new LinkedHashMap<String, Table>();
    private final Map<String, Boolean> tableLoaded = new LinkedHashMap<String, Boolean>();

    private Db(Connection connection, Dialect dialect) {
        this.connection = connection;
        this.dialect = dialect;
        String cat = null;
        String sch = null;
        try {
            cat = trimToNull(connection.getCatalog());
        } catch (Exception ignored) {
            // ignore
        }
        try {
            sch = trimToNull(connection.getSchema());
        } catch (Throwable ignored) {
            // ignore
        }
        this.catalog = cat;
        this.schema = sch;
    }

    public static Db open(String url, String user, String password, String driver, String dialectName,
                          String preferredSchema) {
        // 未显式指定驱动时按 URL 预加载一次：驱动不在 classpath 时能给出
        // 「缺哪个类」的明确提示，而不是等到 DriverManager 报 No suitable driver
        String inferred = null;
        if (driver != null && !driver.trim().isEmpty()) {
            try {
                Class.forName(driver.trim());
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("找不到 JDBC 驱动: " + driver
                        + "；请把驱动 jar 放到 classpath，或用 -cp \"flow-migration.jar;driver.jar\" 启动", e);
            }
        } else {
            inferred = defaultDriver(url);
            if (inferred != null) {
                try {
                    Class.forName(inferred);
                } catch (ClassNotFoundException e) {
                    // 交给下面的 DriverManager 统一报错，保留 inferred 用于提示
                    inferred = inferred + "（不在 classpath）";
                } catch (Throwable ignored) {
                    // 某些驱动静态初始化依赖运行环境，忽略后由 DriverManager 处理
                }
            }
        }
        Properties properties = new Properties();
        if (user != null) {
            properties.setProperty("user", user);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        if (url != null && url.toLowerCase(Locale.ROOT).startsWith("jdbc:mysql:")) {
            properties.setProperty("useUnicode", "true");
            properties.setProperty("characterEncoding", "UTF-8");
            properties.setProperty("zeroDateTimeBehavior", "convertToNull");
            if (!url.toLowerCase(Locale.ROOT).contains("servertimezone")) {
                properties.setProperty("serverTimezone", "Asia/Shanghai");
            }
        }
        try {
            Connection connection = DriverManager.getConnection(url, properties);
            Db db = new Db(connection, Dialect.detect(url, dialectName));
            if (preferredSchema != null && !preferredSchema.trim().isEmpty()) {
                db.useSchema(preferredSchema.trim());
            }
            return db;
        } catch (SQLException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.toLowerCase(Locale.ROOT).contains("no suitable driver")) {
                throw new MigrationException("连接数据库失败: " + url + "\n"
                        + "  找不到可用的 JDBC 驱动" + (inferred == null ? "" : "（" + inferred + "）") + "。\n"
                        + "  - 确认使用完整包 flow-migration.jar（已内置 MySQL/PostgreSQL/瀚高驱动）；\n"
                        + "  - 使用其他厂商驱动时，把驱动 jar 与 flow-migration.jar 一起放到 classpath：\n"
                        + "    java -cp \"flow-migration.jar;your-driver.jar\" com.joinsoft.flow.migration.MigrationMain ...\n"
                        + "  - 或用 --source-driver / --target-driver 显式指定驱动类。", e);
            }
            throw new MigrationException("连接数据库失败: " + url + " (" + message + ")", e);
        }
    }

    /** 按 JDBC URL 前缀推断驱动类，供缺驱动时给出可读提示。 */
    static String defaultDriver(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("jdbc:mysql:")) {
            return "com.mysql.cj.jdbc.Driver";
        }
        if (lower.startsWith("jdbc:mariadb:")) {
            return "org.mariadb.jdbc.Driver";
        }
        if (lower.startsWith("jdbc:postgresql:")) {
            return "org.postgresql.Driver";
        }
        if (lower.startsWith("jdbc:highgo:")) {
            return "com.highgo.jdbc.Driver";
        }
        if (lower.startsWith("jdbc:h2:")) {
            return "org.h2.Driver";
        }
        return null;
    }

    /** 允许显式指定 schema（PostgreSQL/瀚高）或数据库（MySQL）。 */
    private void useSchema(String name) {
        if (dialect == Dialect.POSTGRESQL) {
            try {
                connection.setSchema(name);
                schema = name;
            } catch (Throwable ignored) {
                // 某些驱动不支持，交由兜底逻辑处理
            }
            try {
                Statement statement = connection.createStatement();
                try {
                    statement.execute("SET search_path TO " + name);
                } finally {
                    statement.close();
                }
            } catch (SQLException ignored) {
                // ignore
            }
        } else if (dialect == Dialect.MYSQL) {
            try {
                connection.setCatalog(name);
                catalog = name;
            } catch (Throwable ignored) {
                // ignore
            }
            try {
                Statement statement = connection.createStatement();
                try {
                    statement.execute("USE `" + name.replace("`", "``") + "`");
                } finally {
                    statement.close();
                }
            } catch (SQLException ignored) {
                // ignore
            }
        }
    }

    public Table table(String name) {
        String key = name.toUpperCase(Locale.ROOT);
        if (tableLoaded.containsKey(key)) {
            return tableCache.get(key);
        }
        Table table = loadTable(key);
        tableCache.put(key, table);
        tableLoaded.put(key, Boolean.TRUE);
        return table;
    }

    public Table requireTable(String name) {
        Table table = table(name);
        if (table == null) {
            List<String> available = new ArrayList<String>();
            for (String item : tableNames()) {
                if (item.toLowerCase(Locale.ROOT).startsWith("act_")
                        || item.equalsIgnoreCase("sys_user")
                        || item.toLowerCase(Locale.ROOT).startsWith("flow_")) {
                    available.add(item);
                }
            }
            throw new MigrationException("缺少必需的表: " + name + "；当前连接可见的相关表: " + available);
        }
        return table;
    }

    public List<String> tableNames() {
        List<String> names = new ArrayList<String>();
        try {
            DatabaseMetaData meta = connection.getMetaData();
            ResultSet rs = meta.getTables(catalog, schema, "%", new String[]{"TABLE"});
            try {
                while (rs.next()) {
                    names.add(rs.getString("TABLE_NAME"));
                }
            } finally {
                rs.close();
            }
            if (names.isEmpty() && catalog != null) {
                rs = meta.getTables(catalog, null, "%", new String[]{"TABLE"});
                try {
                    while (rs.next()) {
                        names.add(rs.getString("TABLE_NAME"));
                    }
                } finally {
                    rs.close();
                }
            }
        } catch (SQLException e) {
            throw new MigrationException("读取表清单失败: " + e.getMessage(), e);
        }
        return names;
    }

    private Table loadTable(String upperName) {
        List<String> candidates = new ArrayList<String>();
        try {
            DatabaseMetaData meta = connection.getMetaData();
            ResultSet rs = meta.getTables(catalog, schema, "%", new String[]{"TABLE"});
            try {
                while (rs.next()) {
                    String actual = rs.getString("TABLE_NAME");
                    if (actual != null && actual.toUpperCase(Locale.ROOT).equals(upperName)) {
                        candidates.add(actual);
                    }
                }
            } finally {
                rs.close();
            }
            if (candidates.isEmpty() && schema != null) {
                rs = meta.getTables(catalog, null, "%", new String[]{"TABLE"});
                try {
                    while (rs.next()) {
                        String actual = rs.getString("TABLE_NAME");
                        if (actual != null && actual.toUpperCase(Locale.ROOT).equals(upperName)) {
                            candidates.add(actual);
                        }
                    }
                } finally {
                    rs.close();
                }
            }
            if (candidates.isEmpty()) {
                return null;
            }
            String actual = pickPreferred(candidates);
            Map<String, String> columns = new LinkedHashMap<String, String>();
            rs = meta.getColumns(catalog, schema, actual, "%");
            try {
                while (rs.next()) {
                    String col = rs.getString("COLUMN_NAME");
                    if (col != null) {
                        columns.put(col.toUpperCase(Locale.ROOT), col);
                    }
                }
            } finally {
                rs.close();
            }
            if (columns.isEmpty() && schema != null) {
                rs = meta.getColumns(catalog, null, actual, "%");
                try {
                    while (rs.next()) {
                        String col = rs.getString("COLUMN_NAME");
                        if (col != null) {
                            columns.put(col.toUpperCase(Locale.ROOT), col);
                        }
                    }
                } finally {
                    rs.close();
                }
            }
            return new Table(actual, columns);
        } catch (SQLException e) {
            throw new MigrationException("读取表结构失败: " + upperName + " (" + e.getMessage() + ")", e);
        }
    }

    /** 同名表存在于多个 schema 时，优先当前 schema。 */
    private String pickPreferred(List<String> candidates) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        try {
            String current = connection.getSchema();
            if (current != null) {
                for (String candidate : candidates) {
                    if (candidate.equalsIgnoreCase(current)) {
                        return candidate;
                    }
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return candidates.get(0);
    }

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
            // ignore
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ------------------------------------------------------------------
    // ResultSet 取值辅助
    // ------------------------------------------------------------------

    public static String str(ResultSet rs, String label) {
        try {
            return rs.getString(label);
        } catch (SQLException e) {
            return null;
        }
    }

    public static Integer integer(ResultSet rs, String label) {
        try {
            Object value = rs.getObject(label);
            if (value == null) {
                return null;
            }
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            return Integer.valueOf(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    public static Long longValue(ResultSet rs, String label) {
        try {
            Object value = rs.getObject(label);
            if (value == null) {
                return null;
            }
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
            return Long.valueOf(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    public static Double doubleValue(ResultSet rs, String label) {
        try {
            Object value = rs.getObject(label);
            if (value == null) {
                return null;
            }
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
            return Double.valueOf(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    public static Timestamp timestamp(ResultSet rs, String label) {
        try {
            return rs.getTimestamp(label);
        } catch (SQLException e) {
            return null;
        }
    }

    public static byte[] bytes(ResultSet rs, String label) {
        try {
            return rs.getBytes(label);
        } catch (SQLException e) {
            return null;
        }
    }

    public static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "y".equalsIgnoreCase(text);
    }

    /**
     * 解析后的表：实际表名 + 列名映射（大写 -> 实际列名）。
     */
    public static final class Table {

        public final String name;
        private final Map<String, String> columns;

        Table(String name, Map<String, String> columns) {
            this.name = name;
            this.columns = columns;
        }

        /** 返回第一个存在的列的实际名字，不存在返回 null。 */
        public String col(String... candidates) {
            for (String candidate : candidates) {
                String actual = columns.get(candidate.toUpperCase(Locale.ROOT));
                if (actual != null) {
                    return actual;
                }
            }
            return null;
        }

        public boolean has(String column) {
            return columns.containsKey(column.toUpperCase(Locale.ROOT));
        }

        public List<String> columnNames() {
            return new ArrayList<String>(columns.values());
        }
    }
}
