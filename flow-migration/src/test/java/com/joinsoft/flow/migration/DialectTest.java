package com.joinsoft.flow.migration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 建表脚本切分与方言推断。
 */
class DialectTest {

    @Test
    void splitsOnSemicolonOutsideQuotesAndComments() {
        String script = "-- 头部注释; 不应切断\n"
                + "CREATE TABLE a (x VARCHAR(10));\n"
                + "/* 块注释; 也不应切断 */\n"
                + "INSERT INTO a VALUES ('v;1');\n"
                + "CREATE INDEX \"i;x\" ON a (x);\n";
        List<String> statements = Dialect.splitStatements(script);
        assertEquals(3, statements.size(), statements.toString());
        assertEquals("CREATE TABLE a (x VARCHAR(10))", statements.get(0));
        assertTrue(statements.get(1).contains("'v;1'"), statements.get(1));
        assertTrue(statements.get(2).contains("\"i;x\""), statements.get(2));
    }

    @Test
    void keepsEscapedSingleQuoteInsideLiteral() {
        List<String> statements = Dialect.splitStatements("INSERT INTO a VALUES ('it''s;ok');SELECT 1;");
        assertEquals(2, statements.size(), statements.toString());
        assertEquals("INSERT INTO a VALUES ('it''s;ok')", statements.get(0));
        assertEquals("SELECT 1", statements.get(1));
    }

    @Test
    void bundledDdlIsCleanForEveryDialect() {
        for (Dialect dialect : Dialect.values()) {
            List<String> statements = dialect.ddlStatements();
            assertTrue(statements.size() >= 10, dialect + " 语句数异常: " + statements.size());
            for (String sql : statements) {
                assertFalse(sql.contains("--"), dialect + " 残留行注释: " + sql);
                assertTrue(sql.toLowerCase(Locale.ROOT).startsWith("create"), dialect + " 脏语句: " + sql);
            }
        }
    }

    @Test
    void detectsDialectFromUrl() {
        assertEquals(Dialect.MYSQL, Dialect.detect("jdbc:mysql://host/db", null));
        assertEquals(Dialect.MYSQL, Dialect.detect("jdbc:mariadb://host/db", null));
        assertEquals(Dialect.POSTGRESQL, Dialect.detect("jdbc:postgresql://host/db", null));
        assertEquals(Dialect.POSTGRESQL, Dialect.detect("jdbc:highgo://host/db", null));
        assertEquals(Dialect.H2, Dialect.detect("jdbc:h2:mem:test", null));
        assertEquals(Dialect.POSTGRESQL, Dialect.detect("jdbc:mysql://host/db", "postgresql"));
        assertThrows(IllegalArgumentException.class, () -> Dialect.detect("jdbc:oracle:thin:@host:1521:orcl", null));
    }
}