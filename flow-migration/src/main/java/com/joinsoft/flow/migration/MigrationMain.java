package com.joinsoft.flow.migration;

import java.io.PrintStream;

/**
 * 命令行入口。
 *
 * <pre>
 * java -jar flow-migration.jar \
 *   --source-url jdbc:mysql://old-host:3306/olddb --source-user root --source-password xxx \
 *   --target-url jdbc:postgresql://new-host:5432/newdb --target-user postgres --target-password xxx \
 *   --mode dry-run
 * </pre>
 */
public final class MigrationMain {

    public static final String VERSION = "1.1.1";

    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (RuntimeException e) {
            err.println("参数错误: " + e.getMessage());
            printUsage(err);
            return 2;
        }
        if (options.version) {
            out.println("flow-migration " + VERSION);
            return 0;
        }
        if (options.help) {
            printUsage(out);
            return 0;
        }
        Migrator migrator = new Migrator(options, out);
        try {
            Report report = migrator.run();
            if (options.failOnWarnings && report.totalWarnings() > 0) {
                err.println("迁移完成，但存在 " + report.totalWarnings()
                        + " 条告警（--fail-on-warnings 已开启，按失败处理）");
                return 4;
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("参数错误: " + e.getMessage());
            return 2;
        } catch (MigrationException e) {
            err.println("迁移失败: " + e.getMessage());
            return 3;
        } catch (RuntimeException e) {
            err.println("迁移失败（未预期异常）: " + e);
            e.printStackTrace(err);
            return 3;
        } finally {
            migrator.close();
        }
    }

    private static void printUsage(PrintStream out) {
        out.println("Activiti 6 -> flow-approval 数据迁移工具 v" + VERSION);
        out.println();
        out.println("用法:");
        out.println("  java -jar flow-migration.jar --source-url <旧库> --target-url <新库> [参数]");
        out.println();
        out.println("连接参数:");
        out.println("  --source-url <url>         旧库 JDBC URL（含 ACT_* 表），必填");
        out.println("  --source-user <user>       旧库用户名");
        out.println("  --source-password <pwd>    旧库密码");
        out.println("  --source-schema <schema>   旧库 schema（PostgreSQL/瀚高有效）");
        out.println("  --source-driver <class>    旧库驱动类，例如 com.highgo.jdbc.Driver");
        out.println("  --source-dialect <name>    显式指定 MySQL/PostgreSQL/H2，默认按 URL 推断");
        out.println("  --target-url <url>         新库 JDBC URL（flow_* 表），必填");
        out.println("  --target-user <user>       新库用户名");
        out.println("  --target-password <pwd>    新库密码");
        out.println("  --target-schema <schema>   新库 schema（PostgreSQL/瀚高有效）");
        out.println("  --target-driver <class>    新库驱动类");
        out.println("  --target-dialect <name>    显式指定目标方言");
        out.println();
        out.println("迁移参数:");
        out.println("  --mode dry-run|execute     默认 dry-run，只统计不写入；确认后改 execute");
        out.println("  --tables def,inst,task,comment");
        out.println("                             迁移范围，默认全部；task/comment 必须同时含 inst");
        out.println("  --include-drafts <bool>    是否迁移 ACT_RE_MODEL 草稿，默认 true");
        out.println("  --truncate <bool>          execute 前清空所选目标表，默认 false");
        out.println("  --create-schema <bool>     execute 时自动建 flow_* 表，默认 true");
        out.println("  --assignee-format auto|id|username");
        out.println("                             旧审批人值是用户ID还是用户名，默认 auto");
        out.println("  --page-size <n>            源库分页大小，默认 200");
        out.println("  --batch-size <n>           目标库批量写入大小，默认 200");
        out.println("  --skip-unsupported-instances <bool>");
        out.println("                             运行中实例含子流程/多实例等新引擎不支持的节点时是否跳过，默认 true");
        out.println("  --report <file.csv>        输出迁移报告（阶段汇总 + 逐行告警，UTF-8 BOM，Excel 可直接打开）");
        out.println("  --max-warnings <n>         内存中最多保留多少条告警，默认 2000");
        out.println("  --fail-on-warnings <bool>  有告警时以退出码 4 结束，便于 CI/脚本判定，默认 false");
        out.println("  --config <file.properties> 从配置文件读取参数，命令行优先");
        out.println("  -h, --help                 显示帮助");
        out.println("  --version                  显示版本");
        out.println();
        out.println("退出码:");
        out.println("  0 成功；2 参数错误；3 迁移失败；4 迁移成功但有告警（仅 --fail-on-warnings true）");
        out.println();
        out.println("示例:");
        out.println("  # MySQL -> PostgreSQL 演练（不写入）");
        out.println("  java -jar flow-migration.jar --source-url jdbc:mysql://127.0.0.1:3306/old \\");
        out.println("    --source-user root --source-password 123456 \\");
        out.println("    --target-url jdbc:postgresql://127.0.0.1:5432/new --target-user postgres \\");
        out.println("    --target-password 123456 --mode dry-run");
        out.println();
        out.println("  # MySQL -> 瀚高（PostgreSQL 协议）正式迁移");
        out.println("  java -jar flow-migration.jar --source-url jdbc:mysql://127.0.0.1:3306/old \\");
        out.println("    --source-user root --source-password 123456 \\");
        out.println("    --target-url jdbc:highgo://127.0.0.1:5866/new --target-user highgo \\");
        out.println("    --target-password 123456 --target-driver com.highgo.jdbc.Driver --mode execute");
        out.println();
        out.println("注意:");
        out.println("  1. 旧库 ACT_* 表不会被修改或删除，可反复执行本工具。");
        out.println("  2. 目标 flow_* 表按主键覆盖写入，重复执行结果一致；按阶段提交，中断后可直接重跑。");
        out.println("  3. 正式切换前请先备份两个库，并先跑一次 dry-run 查看告警。");
        out.println("  4. 旧的「撤销」实例在新引擎按已终止（state=3）迁移，因为新引擎的撤销是物理删除。");
        out.println("  5. 执行成功后会尝试补建 business_key 唯一索引；有重复数据时只告警、不建索引。");
    }
}
