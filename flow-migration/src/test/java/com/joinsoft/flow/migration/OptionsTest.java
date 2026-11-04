package com.joinsoft.flow.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参数解析与校验：默认值、配置文件优先级、非法值拦截。
 */
class OptionsTest {

    @Test
    void parsesDefaultsAndFlags() {
        Options options = Options.parse(new String[]{
                "--source-url", "jdbc:mysql://a/db",
                "--target-url", "jdbc:postgresql://b/db",
                "--fail-on-warnings"
        });
        options.validate();
        assertEquals("dry-run", options.mode);
        assertFalse(options.execute());
        assertTrue(options.failOnWarnings);
        assertEquals("def,inst,task,comment", options.tables);
        assertEquals(200, options.pageSize);
        assertEquals(200, options.batchSize);
    }

    @Test
    void commandLineOverridesConfigFile(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("migration.properties");
        String content = "source_url=jdbc:mysql://file/db\n"
                + "target_url=jdbc:postgresql://file/db\n"
                + "mode=execute\n"
                + "page_size=50\n";
        Files.write(config, content.getBytes(StandardCharsets.UTF_8));

        Options options = Options.parse(new String[]{
                "--config", config.toString(),
                "--target-url", "jdbc:postgresql://cli/db"
        });
        options.validate();
        assertEquals("jdbc:mysql://file/db", options.sourceUrl);
        assertEquals("jdbc:postgresql://cli/db", options.targetUrl, "命令行应覆盖配置文件");
        assertTrue(options.execute());
        assertEquals(50, options.pageSize);
    }

    @Test
    void rejectsUnknownTableName() {
        Options options = Options.parse(new String[]{
                "--source-url", "jdbc:mysql://a/db",
                "--target-url", "jdbc:postgresql://b/db",
                "--tables", "def,foo"
        });
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, options::validate);
        assertTrue(e.getMessage().contains("foo"), e.getMessage());
    }

    @Test
    void requiresInstanceStageForTaskAndComment() {
        Options taskOnly = Options.parse(new String[]{
                "--source-url", "jdbc:mysql://a/db",
                "--target-url", "jdbc:postgresql://b/db",
                "--tables", "def,task"
        });
        assertThrows(IllegalArgumentException.class, taskOnly::validate);

        Options commentOnly = Options.parse(new String[]{
                "--source-url", "jdbc:mysql://a/db",
                "--target-url", "jdbc:postgresql://b/db",
                "--tables", "comment"
        });
        assertThrows(IllegalArgumentException.class, commentOnly::validate);
    }

    @Test
    void supportsVersionFlag() {
        assertTrue(Options.parse(new String[]{"--version"}).version);
        assertTrue(Options.parse(new String[]{"-V"}).version);
    }
}