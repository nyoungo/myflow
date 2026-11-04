package com.joinsoft.flow.migration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 目标库方言。只影响建表脚本；读写数据全部走标准 JDBC + DatabaseMetaData，因此
 * MySQL / PostgreSQL / 瀚高（PostgreSQL 协议）/ H2 可以共用同一套迁移逻辑。
 */
public enum Dialect {

    MYSQL("schema/mysql.sql"),
    POSTGRESQL("schema/postgresql.sql"),
    H2("schema/h2.sql");

    private final String ddlResource;

    Dialect(String ddlResource) {
        this.ddlResource = ddlResource;
    }

    public static Dialect detect(String jdbcUrl, String explicit) {
        if (explicit != null && !explicit.trim().isEmpty()) {
            return valueOf(explicit.trim().toUpperCase(Locale.ROOT));
        }
        String url = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(Locale.ROOT);
        if (url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:mariadb:")) {
            return MYSQL;
        }
        if (url.startsWith("jdbc:postgresql:") || url.startsWith("jdbc:highgo:")) {
            return POSTGRESQL;
        }
        if (url.startsWith("jdbc:h2:")) {
            return H2;
        }
        throw new IllegalArgumentException("无法根据 URL 判断数据库类型，请显式指定 --source-dialect/--target-dialect: " + jdbcUrl);
    }

    public List<String> ddlStatements() {
        return splitStatements(readResource(ddlResource));
    }

    /**
     * 按分号切分建表脚本。
     *
     * <p>直接按分号切会被注释或字符串内容里的分号打断，这里先跳过行注释与块注释，
     * 再只在引号外切分，保证脚本以后加入注释/函数体也不会切错。</p>
     */
    static List<String> splitStatements(String text) {
        List<String> statements = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean singleQuote = false;
        boolean doubleQuote = false;
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (!singleQuote && !doubleQuote) {
                if (ch == '-' && i + 1 < text.length() && text.charAt(i + 1) == '-') {
                    while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
                        i++;
                    }
                    current.append('\n');
                    continue;
                }
                if (ch == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                    int end = text.indexOf("*/", i + 2);
                    i = end < 0 ? text.length() : end + 2;
                    current.append(' ');
                    continue;
                }
            }
            if (ch == '\'' && !doubleQuote) {
                // 连续两个单引号是转义，不改变引号状态
                if (singleQuote && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    current.append("''");
                    i += 2;
                    continue;
                }
                singleQuote = !singleQuote;
                current.append(ch);
                i++;
                continue;
            }
            if (ch == '"' && !singleQuote) {
                doubleQuote = !doubleQuote;
                current.append(ch);
                i++;
                continue;
            }
            if (ch == ';' && !singleQuote && !doubleQuote) {
                addStatement(statements, current);
                current.setLength(0);
                i++;
                continue;
            }
            current.append(ch);
            i++;
        }
        addStatement(statements, current);
        return statements;
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String sql = current.toString().trim();
        if (!sql.isEmpty()) {
            statements.add(sql);
        }
    }

    private static String readResource(String name) {
        InputStream in = Dialect.class.getClassLoader().getResourceAsStream(name);
        if (in == null) {
            throw new IllegalStateException("缺少内置建表脚本: " + name);
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取建表脚本失败: " + name, e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }
}
