package com.joinsoft.flow.migration;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 迁移上下文：持有连接、写入器、定义缓存等共享状态。
 */
public final class Ctx {

    public final Options options;
    public final Report report;
    public final Db source;
    public final Db target;
    public final Writer writer;
    public final UserResolver users;

    /** 旧 procDefId -> 定义信息 */
    public final Map<String, DefInfo> definitions = new LinkedHashMap<String, DefInfo>();
    /** 已迁移的流程实例 id */
    public final Set<String> instances = new LinkedHashSet<String>();
    /** 已迁移实例 id -> tenant_id，供任务/意见等子表继承租户 */
    public final Map<String, String> instanceTenants = new LinkedHashMap<String, String>();

    public Ctx(Options options, Report report, Db source, Db target, Writer writer, UserResolver users) {
        this.options = options;
        this.report = report;
        this.source = source;
        this.target = target;
        this.writer = writer;
        this.users = users;
    }

    public boolean dryRun() {
        return !options.execute();
    }

    public static final class DefInfo {

        public String id;
        public String key;
        public String name;
        public Integer version;
        public String status;
        public String bpmnXml;
        /** 新引擎无法执行的节点（会阻断运行中实例的迁移） */
        public final Set<String> blocking = new LinkedHashSet<String>();
        /** 语义会变化的节点（只提示，不阻断） */
        public final Set<String> warnings = new LinkedHashSet<String>();

        public boolean blockingUnsupported() {
            return !blocking.isEmpty();
        }

        public Set<String> unsupported() {
            Set<String> all = new LinkedHashSet<String>(blocking);
            all.addAll(warnings);
            return all;
        }
    }
}
