package com.joinsoft.flow.migration;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 旧库用户映射：
 * <ul>
 *   <li>auto：值能对上 sys_user.id 就按 id，否则按 username 换成 id；</li>
 *   <li>id：原样保留；</li>
 *   <li>username：一律按 username 换成 id。</li>
 * </ul>
 * 同时提供 id -> 展示名，用于 flow_inst.start_user_name、flow_comment.user_name。
 */
public final class UserResolver implements AutoCloseable {

    private final Db db;
    private final Report report;
    private final String mode;
    private final Db.Table table;

    private final Map<String, String> idToName = new HashMap<String, String>();
    private final Map<String, String> usernameToId = new HashMap<String, String>();
    private final Set<String> missingIds = new HashSet<String>();
    private final Set<String> missingUsernames = new HashSet<String>();
    private final Set<String> warned = new HashSet<String>();

    private PreparedStatement byId;
    private PreparedStatement byUsername;

    public UserResolver(Db db, Report report, String mode) {
        this.db = db;
        this.report = report;
        this.mode = mode;
        this.table = db.table("sys_user");
        if (table == null) {
            report.warn("sys_user", "", "旧库缺少 sys_user 表", "不生成用户姓名，审批人/发起人原样保留");
        } else if (table.col("ID_", "ID") == null || table.col("USERNAME_", "USERNAME") == null) {
            report.warn("sys_user", "", "sys_user 缺少 id/username 列", "跳过用户映射");
        }
    }

    public String resolveId(String raw) {
        if (!Text.hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        if (table == null || "id".equalsIgnoreCase(mode)) {
            return value;
        }
        if ("username".equalsIgnoreCase(mode)) {
            String id = idByUsername(value);
            if (id == null) {
                warnOnce(value, "旧值不是已知用户名，按原值保留");
                return value;
            }
            return id;
        }
        // auto
        if (nameById(value) != null) {
            return value;
        }
        String id = idByUsername(value);
        if (id != null) {
            return id;
        }
        warnOnce(value, "旧值既不是 sys_user.id 也不是 username，按原值保留");
        return value;
    }

    public String name(String userId) {
        if (!Text.hasText(userId)) {
            return null;
        }
        return nameById(userId.trim());
    }

    private String nameById(String id) {
        if (idToName.containsKey(id)) {
            return idToName.get(id);
        }
        if (missingIds.contains(id) || table == null) {
            return null;
        }
        PreparedStatement statement = byIdStatement();
        if (statement == null) {
            return null;
        }
        ResultSet rs = null;
        try {
            statement.setString(1, id);
            rs = statement.executeQuery();
            if (rs.next()) {
                String name = displayName(rs);
                idToName.put(id, name);
                String username = Db.str(rs, "username");
                if (username != null) {
                    usernameToId.put(username, id);
                }
                return name;
            }
            missingIds.add(id);
            return null;
        } catch (SQLException e) {
            throw new MigrationException("查询 sys_user 失败: " + e.getMessage(), e);
        } finally {
            close(rs);
        }
    }

    private String idByUsername(String username) {
        if (usernameToId.containsKey(username)) {
            return usernameToId.get(username);
        }
        if (missingUsernames.contains(username) || table == null) {
            return null;
        }
        PreparedStatement statement = byUsernameStatement();
        if (statement == null) {
            return null;
        }
        ResultSet rs = null;
        try {
            statement.setString(1, username);
            rs = statement.executeQuery();
            if (rs.next()) {
                String id = Db.str(rs, "id");
                if (id != null) {
                    usernameToId.put(username, id);
                    idToName.put(id, displayName(rs));
                    return id;
                }
            }
            missingUsernames.add(username);
            return null;
        } catch (SQLException e) {
            throw new MigrationException("查询 sys_user 失败: " + e.getMessage(), e);
        } finally {
            close(rs);
        }
    }

    private String displayName(ResultSet rs) {
        String realname = table.col("REALNAME_", "REALNAME", "REAL_NAME_", "NAME_") == null
                ? null : Db.str(rs, "realname");
        String username = Db.str(rs, "username");
        return Text.firstNonEmpty(realname, username, Db.str(rs, "id"));
    }

    private PreparedStatement byIdStatement() {
        if (byId != null) {
            return byId;
        }
        if (table == null) {
            return null;
        }
        String idCol = table.col("ID_", "ID");
        String usernameCol = table.col("USERNAME_", "USERNAME");
        String realnameCol = table.col("REALNAME_", "REALNAME", "REAL_NAME_", "NAME_");
        if (idCol == null) {
            return null;
        }
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(idCol).append(" AS id");
        if (usernameCol != null) {
            sql.append(", ").append(usernameCol).append(" AS username");
        }
        if (realnameCol != null) {
            sql.append(", ").append(realnameCol).append(" AS realname");
        }
        sql.append(" FROM ").append(table.name).append(" WHERE ").append(idCol).append(" = ?");
        try {
            byId = db.connection.prepareStatement(sql.toString());
            return byId;
        } catch (SQLException e) {
            throw new MigrationException("准备 sys_user 查询失败: " + e.getMessage(), e);
        }
    }

    private PreparedStatement byUsernameStatement() {
        if (byUsername != null) {
            return byUsername;
        }
        if (table == null) {
            return null;
        }
        String idCol = table.col("ID_", "ID");
        String usernameCol = table.col("USERNAME_", "USERNAME");
        String realnameCol = table.col("REALNAME_", "REALNAME", "REAL_NAME_", "NAME_");
        if (idCol == null || usernameCol == null) {
            return null;
        }
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(idCol).append(" AS id, ").append(usernameCol).append(" AS username");
        if (realnameCol != null) {
            sql.append(", ").append(realnameCol).append(" AS realname");
        }
        sql.append(" FROM ").append(table.name).append(" WHERE ").append(usernameCol).append(" = ?");
        try {
            byUsername = db.connection.prepareStatement(sql.toString());
            return byUsername;
        } catch (SQLException e) {
            throw new MigrationException("准备 sys_user 查询失败: " + e.getMessage(), e);
        }
    }

    private void warnOnce(String value, String detail) {
        if (warned.add(value)) {
            report.warn("sys_user", value, "用户映射失败", detail);
        }
    }

    @Override
    public void close() {
        closeQuietly(byId);
        closeQuietly(byUsername);
    }

    private static void closeQuietly(PreparedStatement statement) {
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException ignored) {
                // ignore
            }
        }
    }

    private static void close(ResultSet rs) {
        if (rs != null) {
            try {
                rs.close();
            } catch (SQLException ignored) {
                // ignore
            }
        }
    }
}
