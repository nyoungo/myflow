package com.joinsoft.flow.migration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 连接层：URL -> 驱动类推断，用于缺驱动时给出可读提示。
 */
class DbTest {

    @Test
    void infersDriverFromJdbcUrl() {
        assertEquals("com.mysql.cj.jdbc.Driver", Db.defaultDriver("jdbc:mysql://host/db"));
        assertEquals("org.mariadb.jdbc.Driver", Db.defaultDriver("jdbc:mariadb://host/db"));
        assertEquals("org.postgresql.Driver", Db.defaultDriver("jdbc:postgresql://host/db"));
        assertEquals("com.highgo.jdbc.Driver", Db.defaultDriver("jdbc:highgo://host:5866/db"));
        assertEquals("org.h2.Driver", Db.defaultDriver("jdbc:h2:mem:test"));
        assertNull(Db.defaultDriver("jdbc:oracle:thin:@host:1521:orcl"));
        assertNull(Db.defaultDriver(null));
    }

    @Test
    void highgoDriverIsOnClasspath() throws Exception {
        // 打包时必须内置瀚高驱动，否则 jdbc:highgo: 会报 No suitable driver
        Class.forName("com.highgo.jdbc.Driver");
    }
}
