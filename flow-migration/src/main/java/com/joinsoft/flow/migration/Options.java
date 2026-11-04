package com.joinsoft.flow.migration;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * 命令行/配置文件参数。
 */
public final class Options {

    public String sourceUrl;
    public String sourceUser;
    public String sourcePassword;
    public String sourceSchema;
    public String sourceDriver;
    public String sourceDialect;

    public String targetUrl;
    public String targetUser;
    public String targetPassword;
    public String targetSchema;
    public String targetDriver;
    public String targetDialect;

    /** dry-run | execute */
    public String mode = "dry-run";
    /** def,inst,task,comment 的任意组合 */
    public String tables = "def,inst,task,comment";
    public boolean includeDrafts = true;
    public boolean truncate = false;
    public boolean createSchema = true;
    public int pageSize = 200;
    public int batchSize = 200;
    public boolean skipUnsupportedInstances = true;
    /** auto | id | username */
    public String assigneeFormat = "auto";
    public String reportPath;
    public int maxWarnings = 2000;
    /** 迁移产生告警时是否让进程以非 0 退出（便于 CI/脚本判定） */
    public boolean failOnWarnings = false;
    public boolean help;
    public boolean version;

    private final Map<String, String> raw = new LinkedHashMap<String, String>();

    public static Options parse(String[] args) {
        Options options = new Options();
        String configPath = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("-h".equals(arg) || "--help".equals(arg)) {
                options.help = true;
                continue;
            }
            if ("--version".equals(arg) || "-V".equals(arg)) {
                options.version = true;
                continue;
            }
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("无法识别的参数: " + arg);
            }
            String key;
            String value;
            int eq = arg.indexOf('=');
            if (eq > 0) {
                key = arg.substring(2, eq);
                value = arg.substring(eq + 1);
            } else {
                key = arg.substring(2);
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    value = "true";
                } else {
                    value = args[++i];
                }
            }
            if ("config".equals(key)) {
                configPath = value;
            } else {
                options.raw.put(normalize(key), value);
            }
        }
        if (configPath != null) {
            options.loadConfig(configPath);
        }
        options.apply();
        return options;
    }

    private void loadConfig(String path) {
        Properties properties = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(path);
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalArgumentException("读取配置文件失败: " + path + " (" + e.getMessage() + ")", e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
        for (String name : properties.stringPropertyNames()) {
            String value = properties.getProperty(name);
            if (value != null) {
                // 配置文件优先级低于命令行
                raw.putIfAbsent(normalize(name), value.trim());
            }
        }
    }

    private static String normalize(String key) {
        String result = key.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (result.startsWith("source.") || result.startsWith("target.")) {
            result = result.replace('.', '-');
        }
        return result;
    }

    private void apply() {
        sourceUrl = get("source-url", sourceUrl);
        sourceUser = get("source-user", sourceUser);
        sourcePassword = get("source-password", sourcePassword);
        sourceSchema = get("source-schema", sourceSchema);
        sourceDriver = get("source-driver", sourceDriver);
        sourceDialect = get("source-dialect", sourceDialect);

        targetUrl = get("target-url", targetUrl);
        targetUser = get("target-user", targetUser);
        targetPassword = get("target-password", targetPassword);
        targetSchema = get("target-schema", targetSchema);
        targetDriver = get("target-driver", targetDriver);
        targetDialect = get("target-dialect", targetDialect);

        mode = get("mode", mode);
        tables = get("tables", tables);
        includeDrafts = getBoolean("include-drafts", includeDrafts);
        truncate = getBoolean("truncate", truncate);
        createSchema = getBoolean("create-schema", createSchema);
        pageSize = getInt("page-size", pageSize);
        batchSize = getInt("batch-size", batchSize);
        skipUnsupportedInstances = getBoolean("skip-unsupported-instances", skipUnsupportedInstances);
        assigneeFormat = get("assignee-format", assigneeFormat);
        reportPath = get("report", reportPath);
        maxWarnings = getInt("max-warnings", maxWarnings);
        failOnWarnings = getBoolean("fail-on-warnings", failOnWarnings);
    }

    private String get(String key, String defaultValue) {
        String value = raw.get(key);
        return value == null ? defaultValue : value;
    }

    private boolean getBoolean(String key, boolean defaultValue) {
        String value = raw.get(key);
        if (value == null) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    private int getInt(String key, int defaultValue) {
        String value = raw.get(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 --" + key + " 需要整数: " + value);
        }
    }

    public boolean wants(String table) {
        for (String item : tables.split(",")) {
            if (item.trim().equalsIgnoreCase(table)) {
                return true;
            }
        }
        return false;
    }

    public boolean execute() {
        return "execute".equalsIgnoreCase(mode);
    }

    public void validate() {
        if (sourceUrl == null || sourceUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 --source-url（旧库，含 ACT_* 表）");
        }
        if (targetUrl == null || targetUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 --target-url（新库，含 flow_* 表）");
        }
        if (!"dry-run".equalsIgnoreCase(mode) && !"execute".equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException("--mode 只能是 dry-run 或 execute");
        }
        if (pageSize <= 0 || pageSize > 5000) {
            throw new IllegalArgumentException("--page-size 取值 1..5000");
        }
        if (batchSize <= 0 || batchSize > 5000) {
            throw new IllegalArgumentException("--batch-size 取值 1..5000");
        }
        if (!"auto".equalsIgnoreCase(assigneeFormat)
                && !"id".equalsIgnoreCase(assigneeFormat)
                && !"username".equalsIgnoreCase(assigneeFormat)) {
            throw new IllegalArgumentException("--assignee-format 只能是 auto / id / username");
        }
        if (maxWarnings < 0) {
            throw new IllegalArgumentException("--max-warnings 不能为负数");
        }
        for (String item : tables.split(",")) {
            String name = item.trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            if (!"def".equals(name) && !"inst".equals(name) && !"task".equals(name) && !"comment".equals(name)) {
                throw new IllegalArgumentException("--tables 只支持 def/inst/task/comment，无法识别: " + item.trim());
            }
        }
        if (!wants("def") && !wants("inst") && !wants("task") && !wants("comment")) {
            throw new IllegalArgumentException("--tables 至少要包含 def/inst/task/comment 之一");
        }
        if (wants("task") && !wants("inst")) {
            throw new IllegalArgumentException("迁移 flow_task 依赖 flow_inst，请同时包含 inst");
        }
        if (wants("comment") && !wants("inst")) {
            throw new IllegalArgumentException("迁移 flow_comment 依赖 flow_inst，请同时包含 inst");
        }
    }
}
