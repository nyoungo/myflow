package com.joinsoft.flow.migration;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 迁移报告：每个阶段的读取/写入/跳过计数，以及逐行告警。
 */
public final class Report {

    private final Map<String, long[]> counters = new LinkedHashMap<String, long[]>();
    private final List<String[]> warnings = new ArrayList<String[]>();
    private final int maxWarnings;
    private long suppressedWarnings;
    private final PrintStream out;

    public Report(PrintStream out, int maxWarnings) {
        this.out = out;
        this.maxWarnings = maxWarnings;
    }

    public void info(String message) {
        out.println(message);
    }

    public void stage(String name, long read, long written, long skipped) {
        counters.put(name, new long[]{read, written, skipped});
        out.println(String.format("  %-14s 读取 %8d  写入 %8d  跳过 %8d", name, read, written, skipped));
    }

    public void warn(String table, String id, String issue, String detail) {
        if (warnings.size() >= maxWarnings) {
            suppressedWarnings++;
            return;
        }
        warnings.add(new String[]{table, safe(id), issue, safe(detail)});
    }

    public long warnings() {
        return warnings.size();
    }

    /** 告警总数：包含因 --max-warnings 被截断未保留的部分。 */
    public long totalWarnings() {
        return warnings.size() + suppressedWarnings;
    }

    public long suppressedWarnings() {
        return suppressedWarnings;
    }

    public void printWarningSummary() {
        if (warnings.isEmpty()) {
            out.println("  没有告警。");
            return;
        }
        out.println("  告警 " + warnings.size() + " 条" + (suppressedWarnings > 0 ? "（另有 " + suppressedWarnings + " 条被截断）" : "") + "：");
        int limit = Math.min(warnings.size(), 30);
        for (int i = 0; i < limit; i++) {
            String[] warning = warnings.get(i);
            out.println("    - [" + warning[0] + "] " + warning[1] + " " + warning[2]
                    + (warning[3].isEmpty() ? "" : " : " + warning[3]));
        }
        if (warnings.size() > limit) {
            out.println("    ... 其余 " + (warnings.size() - limit) + " 条见报告文件");
        }
    }

    public void writeCsv(String path) {
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        Path file = java.nio.file.Paths.get(path.trim());
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            PrintWriter writer = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8));
            try {
                writer.write('\ufeff');
                writeSummary(writer);
                writer.println();
                writer.println("告警明细");
                writer.println("表,主键,问题,详情");
                for (String[] warning : warnings) {
                    writer.println(csv(warning[0]) + "," + csv(warning[1]) + ","
                            + csv(warning[2]) + "," + csv(warning[3]));
                }
                if (suppressedWarnings > 0) {
                    writer.println("截断,," + suppressedWarnings + " 条告警未写入,可调大 --max-warnings");
                }
            } finally {
                writer.close();
            }
            out.println("  报告已写入: " + file.toAbsolutePath());
        } catch (IOException e) {
            out.println("  写报告文件失败: " + e.getMessage());
        }
    }

    /** 报告开头的阶段汇总，便于把 CSV 直接当成迁移记录归档。 */
    private void writeSummary(PrintWriter writer) {
        writer.println("阶段汇总");
        writer.println("阶段,读取,写入,跳过");
        long read = 0;
        long written = 0;
        long skipped = 0;
        for (Map.Entry<String, long[]> entry : counters.entrySet()) {
            long[] value = entry.getValue();
            read += value[0];
            written += value[1];
            skipped += value[2];
            writer.println(csv(entry.getKey()) + "," + value[0] + "," + value[1] + "," + value[2]);
        }
        writer.println("合计," + read + "," + written + "," + skipped);
        writer.println("告警数," + totalWarnings() + ",,");
    }

    public void printHeader(Options options, Dialect sourceDialect, Dialect targetDialect) {
        out.println("============================================================");
        out.println("Activiti 6 -> flow-approval 数据迁移 v" + MigrationMain.VERSION);
        out.println("时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        out.println("模式: " + (options.execute() ? "execute（会写入目标库）" : "dry-run（只统计，不写入）"));
        out.println("旧库: " + mask(options.sourceUrl) + "  [" + sourceDialect + "]");
        out.println("新库: " + mask(options.targetUrl) + "  [" + targetDialect + "]");
        out.println("范围: " + options.tables + (options.includeDrafts ? "（含草稿模型）" : "（不含草稿模型）"));
        out.println("============================================================");
    }

    public void printCounters() {
        out.println("--------------- 结果 ----------------");
        long read = 0;
        long written = 0;
        long skipped = 0;
        for (Map.Entry<String, long[]> entry : counters.entrySet()) {
            read += entry.getValue()[0];
            written += entry.getValue()[1];
            skipped += entry.getValue()[2];
        }
        out.println(String.format("  合计 读取 %d，写入 %d，跳过 %d，告警 %d%s",
                read, written, skipped, totalWarnings(),
                suppressedWarnings > 0 ? "（含截断 " + suppressedWarnings + " 条）" : ""));
    }

    private static String mask(String url) {
        if (url == null) {
            return "";
        }
        int index = url.indexOf('?');
        return index > 0 ? url.substring(0, index) : url;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String csv(String value) {
        String text = safe(value).replace("\r", " ").replace("\n", " ");
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }
}
