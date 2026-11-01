package com.joinsoft.flow.migration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 H2 模拟 Activiti 6 库（大写表名，接近 MySQL）和 flow-approval 新库（小写表名，
 * 接近 PostgreSQL），验证定义/实例/任务/意见的端到端迁移、dry-run 与幂等重跑。
 */
class MigrationH2Test {

    private static final String BPMN = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
            + "xmlns:activiti=\"http://activiti.org/bpmn\" targetNamespace=\"test\">\n"
            + "  <process id=\"leave\" name=\"请假流程\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"start\" name=\"开始\"/>\n"
            + "    <userTask id=\"deptApprove\" name=\"部门审批\" activiti:assignee=\"${deptLeader}\"/>\n"
            + "    <userTask id=\"hrApprove\" name=\"人事审批\"/>\n"
            + "    <endEvent id=\"end\" name=\"结束\"/>\n"
            + "  </process>\n"
            + "</definitions>";

    private static final String DRAFT_BPMN = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"test\">\n"
            + "  <process id=\"leaveDraft\" name=\"请假流程草稿\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"start\"/>\n"
            + "    <userTask id=\"draftTask\" name=\"草稿审批\"/>\n"
            + "    <endEvent id=\"end\"/>\n"
            + "  </process>\n"
            + "</definitions>";

    private static final String UNSUPPORTED_BPMN = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"test\">\n"
            + "  <process id=\"complex\" name=\"复杂流程\" isExecutable=\"true\">\n"
            + "    <startEvent id=\"start\"/>\n"
            + "    <subProcess id=\"sub\"><userTask id=\"inner\" name=\"子流程审批\"/></subProcess>\n"
            + "    <endEvent id=\"end\"/>\n"
            + "  </process>\n"
            + "</definitions>";

    @Test
    void migratesDefinitionsInstancesTasksAndComments() throws Exception {
        String sourceUrl = "jdbc:h2:mem:act1;MODE=MySQL;DB_CLOSE_DELAY=-1";
        String targetUrl = "jdbc:h2:mem:flow1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Connection source = DriverManager.getConnection(sourceUrl, "sa", "");
        createActivitiSchema(source);
        seed(source);

        // 1. dry-run 不建表、不写数据
        String dryOutput = runMigration(sourceUrl, targetUrl, "dry-run", false);
        Connection target = DriverManager.getConnection(targetUrl, "sa", "");
        assertFalse(tableExists(target, "flow_inst"), "dry-run 不应创建目标表");
        assertTrue(dryOutput.contains("dry-run"), dryOutput);

        // 2. 正式执行
        runMigration(sourceUrl, targetUrl, "execute", false);
        assertEquals(2, count(target, "flow_def"));
        assertEquals(4, count(target, "flow_inst"));
        assertEquals(6, count(target, "flow_task"));
        assertEquals(2, count(target, "flow_comment"));

        assertDefinition(target);
        assertRunningInstance(target);
        assertFinishedInstance(target);
        assertOrphanInstance(target);
        assertTerminatedInstance(target);
        assertTasks(target);
        assertComments(target);

        // 3. 幂等：重复执行，数据量不变
        runMigration(sourceUrl, targetUrl, "execute", false);
        assertEquals(2, count(target, "flow_def"));
        assertEquals(4, count(target, "flow_inst"));
        assertEquals(6, count(target, "flow_task"));
        assertEquals(2, count(target, "flow_comment"));
        assertEquals(3, tokensOf(target, "p1").size(), "重跑后令牌不应重复");

        // 4. --truncate 后重建，结果仍然一致
        runMigration(sourceUrl, targetUrl, "execute", true);
        assertEquals(2, count(target, "flow_def"));
        assertEquals(4, count(target, "flow_inst"));
        assertEquals(6, count(target, "flow_task"));
        assertEquals(2, count(target, "flow_comment"));

        target.close();
        source.close();
    }

    @Test
    void migratesWithPostgresqlDialectAndLowercaseTarget() throws Exception {
        String sourceUrl = "jdbc:h2:mem:act3;MODE=MySQL;DB_CLOSE_DELAY=-1";
        String targetUrl = "jdbc:h2:mem:flow3;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Connection source = DriverManager.getConnection(sourceUrl, "sa", "");
        createActivitiSchema(source);
        seed(source);

        runMigration(sourceUrl, targetUrl, "execute", false, "POSTGRESQL");

        Connection target = DriverManager.getConnection(targetUrl, "sa", "");
        assertEquals(2, count(target, "flow_def"));
        assertEquals(4, count(target, "flow_inst"));
        assertEquals(6, count(target, "flow_task"));
        assertEquals(2, count(target, "flow_comment"));
        assertEquals(3, tokensOf(target, "p1").size());
        assertEquals("张三", text(target, "SELECT start_user_name FROM flow_inst WHERE id = 'p1'"));
        assertEquals("PUBLISHED", text(target, "SELECT status FROM flow_def WHERE id = 'leave:1:111'"));

        target.close();
        source.close();
    }

    @Test
    void skipsRunningInstanceWithUnsupportedNodes() throws Exception {
        String sourceUrl = "jdbc:h2:mem:act2;MODE=MySQL;DB_CLOSE_DELAY=-1";
        String targetUrl = "jdbc:h2:mem:flow2;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Connection source = DriverManager.getConnection(sourceUrl, "sa", "");
        createActivitiSchema(source);
        insert(source, "ACT_RE_DEPLOYMENT", "ID_,NAME_,DEPLOY_TIME_", "d2", "complex", now());
        insert(source, "ACT_GE_BYTEARRAY", "ID_,NAME_,DEPLOYMENT_ID_,BYTES_",
                "bytes2", "complex.bpmn20.xml", "d2", bytes(UNSUPPORTED_BPMN));
        insert(source, "ACT_RE_PROCDEF",
                "ID_,NAME_,KEY_,VERSION_,DEPLOYMENT_ID_,RESOURCE_NAME_,SUSPENSION_STATE_",
                "complex:1:1", "复杂流程", "complex", 1, "d2", "complex.bpmn20.xml", 1);
        insert(source, "ACT_HI_PROCINST",
                "ID_,PROC_INST_ID_,BUSINESS_KEY_,PROC_DEF_ID_,START_TIME_,START_USER_ID_",
                "px", "px", "complex.BX", "complex:1:1", now(), "u1");
        insert(source, "act_business_data",
                "ID_,PROC_INST_ID_,BUSINESS_ID_,BUSINESS_KEY_,START_USER_ID_,START_TIME_,TITLE_,STATE_",
                "bx", "px", "BX", "complex.BX", "u1", now(), "复杂业务", "1");
        insert(source, "ACT_RU_TASK",
                "ID_,NAME_,CREATE_TIME_,ASSIGNEE_,PROC_INST_ID_,PROC_DEF_ID_,TASK_DEF_KEY_",
                "tx", "内部审批", now(), "u1", "px", "complex:1:1", "inner");

        String output = runMigration(sourceUrl, targetUrl, "execute", false);
        Connection target = DriverManager.getConnection(targetUrl, "sa", "");
        assertEquals(1, count(target, "flow_def"));
        assertEquals(0, count(target, "flow_inst"), "含子流程的运行中实例应被跳过");
        assertTrue(output.contains("跳过运行中实例"), output);

        target.close();
        source.close();
    }

    @Test
    void convertsActivitiDesignerJsonDraftToBpmnXml() throws Exception {
        String sourceUrl = "jdbc:h2:mem:act4;MODE=MySQL;DB_CLOSE_DELAY=-1";
        String targetUrl = "jdbc:h2:mem:flow4;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Connection source = DriverManager.getConnection(sourceUrl, "sa", "");
        createActivitiSchema(source);

        // 未部署的草稿：EDITOR_SOURCE_VALUE_ID_ 指向 Activiti 设计器 JSON，没有部署快照
        String json = resource("/draft-model.json");
        Timestamp base = Timestamp.valueOf("2024-03-01 09:00:00");
        insert(source, "ACT_GE_BYTEARRAY", "ID_,NAME_,DEPLOYMENT_ID_,BYTES_",
                "bytesJson", "model.json", null, bytes(json));
        insert(source, "ACT_RE_MODEL",
                "ID_,REV_,NAME_,KEY_,CATEGORY_,CREATE_TIME_,LAST_UPDATE_TIME_,VERSION_,"
                        + "EDITOR_SOURCE_VALUE_ID_,TENANT_ID_,META_INFO_",
                "m2", 1, "JSON草稿流程", "leaveJson", "1", base, base, 1, "bytesJson", "t1",
                "{\"name\":\"JSON草稿流程\",\"revision\":1,\"description\":\"JSON 草稿描述\"}");

        runMigration(sourceUrl, targetUrl, "execute", false);

        Connection target = DriverManager.getConnection(targetUrl, "sa", "");
        assertEquals(1, count(target, "flow_def"));
        assertEquals("DRAFT", text(target, "SELECT status FROM flow_def WHERE id = 'm2'"));
        String xml = text(target, "SELECT bpmn_xml FROM flow_def WHERE id = 'm2'");
        assertNotNull(xml, "设计器 JSON 应被转换成 BPMN XML");
        assertTrue(xml.contains("<process"), xml);
        assertTrue(xml.contains("id=\"leaveJson\""), xml);
        assertTrue(xml.contains("id=\"start\""), xml);
        assertTrue(xml.contains("id=\"jsonApprove\""), xml);
        assertTrue(xml.contains("id=\"end\""), xml);
        assertTrue(xml.contains("assignee=\"u2\""), xml);
        assertTrue(xml.contains("candidateGroups"), xml);
        assertTrue(xml.contains("userFilter") && xml.contains("deptLeader"), "应保留 userFilter 扩展属性:\n" + xml);
        assertTrue(xml.contains("autoPass"), "应保留 autoPass 扩展属性:\n" + xml);
        assertTrue(xml.contains("noticeStartUser"), "应保留 noticeStartUser 扩展属性:\n" + xml);

        target.close();
        source.close();
    }

    // ------------------------------------------------------------------
    // 断言
    // ------------------------------------------------------------------

    private static void assertDefinition(Connection target) throws SQLException {
        String sql = "SELECT def_name, def_key, version, status, bpmn_xml FROM flow_def WHERE id = 'leave:1:111'";
        PreparedStatement statement = target.prepareStatement(sql);
        try {
            ResultSet rs = statement.executeQuery();
            assertTrue(rs.next());
            assertEquals("请假流程", rs.getString("def_name"));
            assertEquals("leave", rs.getString("def_key"));
            assertEquals(1, rs.getInt("version"));
            assertEquals("PUBLISHED", rs.getString("status"));
            assertTrue(rs.getString("bpmn_xml").contains("deptApprove"));
        } finally {
            statement.close();
        }
        assertEquals("DRAFT", text(target, "SELECT status FROM flow_def WHERE id = 'm1'"));
        assertEquals(0, number(target, "SELECT version FROM flow_def WHERE id = 'm1'"));
        assertTrue(text(target, "SELECT bpmn_xml FROM flow_def WHERE id = 'm1'").contains("draftTask"));
        assertEquals("草稿描述", text(target, "SELECT description FROM flow_def WHERE id = 'm1'"),
                "草稿描述应取自旧 ACT_RE_MODEL.META_INFO_");
        String meta = text(target, "SELECT meta_info FROM flow_def WHERE id = 'leave:1:111'");
        assertNotNull(meta, "应写入新引擎兼容的 meta_info");
        assertTrue(meta.contains("\"name\":\"请假流程\""), meta);
        assertTrue(meta.contains("\"revision\":1"), meta);
    }

    private static void assertRunningInstance(Connection target) throws Exception {
        String sql = "SELECT business_key, business_id, start_user_id, start_user_name, state, task_id, "
                + "task_name, def_key, def_name, variables, active_tokens, activities "
                + "FROM flow_inst WHERE id = 'p1'";
        PreparedStatement statement = target.prepareStatement(sql);
        try {
            ResultSet rs = statement.executeQuery();
            assertTrue(rs.next());
            assertEquals("leave.BIZ-1", rs.getString("business_key"));
            assertEquals("BIZ-1", rs.getString("business_id"));
            assertEquals("u1", rs.getString("start_user_id"));
            assertEquals("张三", rs.getString("start_user_name"));
            assertEquals("1", rs.getString("state"));
            assertEquals("leave", rs.getString("def_key"));
            assertEquals("请假流程", rs.getString("def_name"));
            assertTrue(rs.getString("task_name").contains("部门审批"));
            assertTrue(rs.getString("task_name").contains("人事审批"));

            JsonNode variables = Json.MAPPER.readTree(rs.getString("variables"));
            assertEquals(3, variables.get("days").asInt());
            assertEquals(20.5d, variables.get("amount").asDouble(), 0.0001d);
            assertTrue(variables.get("approved").asBoolean());
            assertEquals(1, variables.get("form").get("a").asInt());
            assertEquals("事假", variables.get("reason").asText(), "应按更新时间取最新值");
            assertFalse(variables.has("blob"), "serializable 变量应跳过");
            assertEquals("local", variables.get("localVar").asText());
            assertEquals("张三", variables.get("startUserName").asText(),
                    "旧 startUserName 是 username，应覆盖为 realname");
            assertEquals(1700000000000L, variables.get("submitDate").asLong(), "date 变量应保留毫秒时间戳");

            JsonNode activities = Json.MAPPER.readTree(rs.getString("activities"));
            assertEquals(3, activities.size());
        } finally {
            statement.close();
        }

        Set<String> nodes = new LinkedHashSet<String>();
        for (JsonNode token : tokensOf(target, "p1")) {
            nodes.add(token.get("node").asText());
        }
        assertEquals(new LinkedHashSet<String>() {{
            add("deptApprove");
            add("hrApprove");
            add("countersign");
        }}, nodes);
    }

    private static void assertFinishedInstance(Connection target) throws SQLException {
        assertEquals("2", text(target, "SELECT state FROM flow_inst WHERE id = 'p2'"));
        assertEquals("已完成的请假", text(target, "SELECT title FROM flow_inst WHERE id = 'p2'"));
        assertEquals("leave.BIZ-2", text(target, "SELECT business_key FROM flow_inst WHERE id = 'p2'"));
    }

    private static void assertOrphanInstance(Connection target) throws SQLException {
        assertEquals("4", text(target, "SELECT state FROM flow_inst WHERE id = 'p3'"));
        assertEquals("u3", text(target, "SELECT start_user_id FROM flow_inst WHERE id = 'p3'"));
        assertEquals("王五", text(target, "SELECT start_user_name FROM flow_inst WHERE id = 'p3'"));
        assertEquals("leave.BIZ-3", text(target, "SELECT business_key FROM flow_inst WHERE id = 'p3'"));
        assertEquals("BIZ-3", text(target, "SELECT business_id FROM flow_inst WHERE id = 'p3'"));
    }

    private static void assertTerminatedInstance(Connection target) throws SQLException {
        assertEquals("3", text(target, "SELECT state FROM flow_inst WHERE id = 'p4'"),
                "已结束 + flow=0 应识别为已终止");
    }

    private static void assertTasks(Connection target) throws SQLException {
        assertEquals("ACTIVE", text(target, "SELECT status FROM flow_task WHERE id = 't1'"));
        assertEquals("Tt1", text(target, "SELECT token_id FROM flow_task WHERE id = 't1'"));
        assertEquals("u2", text(target, "SELECT assignee FROM flow_task WHERE id = 't1'"));
        assertEquals("ACTIVE", text(target, "SELECT status FROM flow_task WHERE id = 't2'"));
        assertEquals(",manager,", text(target, "SELECT candidate_groups FROM flow_task WHERE id = 't2'"));
        assertEquals("COMPLETED", text(target, "SELECT status FROM flow_task WHERE id = 't3'"));
        assertEquals(1000L, number(target, "SELECT duration FROM flow_task WHERE id = 't3'"));
        assertEquals("COMPLETED", text(target, "SELECT status FROM flow_task WHERE id = 't4'"));
        assertEquals("COMPLETED", text(target, "SELECT status FROM flow_task WHERE id = 't5'"));
        assertEquals("ACTIVE", text(target, "SELECT status FROM flow_task WHERE id = 't6'"));
        assertNull(text(target, "SELECT assignee FROM flow_task WHERE id = 't6'"),
                "多人 ASSIGNEE_ 不应写入新引擎的单人 assignee");
        assertEquals(",u2,u3,", text(target, "SELECT candidate_users FROM flow_task WHERE id = 't6'"),
                "多人 ASSIGNEE_ 应拆分为 candidate_users");
    }

    private static void assertComments(Connection target) throws SQLException {
        String sql = "SELECT comment_type, task_def_key, task_name, user_id, user_name, message, flow_state "
                + "FROM flow_comment WHERE id = 'c1'";
        PreparedStatement statement = target.prepareStatement(sql);
        try {
            ResultSet rs = statement.executeQuery();
            assertTrue(rs.next());
            assertEquals("comment", rs.getString("comment_type"));
            assertEquals("deptApprove", rs.getString("task_def_key"));
            assertEquals("部门审批", rs.getString("task_name"));
            assertEquals("u2", rs.getString("user_id"));
            assertEquals("李四", rs.getString("user_name"));
            assertEquals("同意", rs.getString("message"));
            assertEquals("1", rs.getString("flow_state"));
        } finally {
            statement.close();
        }
        assertEquals("system", text(target, "SELECT comment_type FROM flow_comment WHERE id = 'c2'"));
        assertEquals("linked", text(target, "SELECT message FROM flow_comment WHERE id = 'c2'"));
        assertEquals("hrApprove", text(target, "SELECT task_def_key FROM flow_comment WHERE id = 'c2'"));
    }

    // ------------------------------------------------------------------
    // 种子数据
    // ------------------------------------------------------------------

    private static void seed(Connection c) throws SQLException {
        insert(c, "sys_user", "id,username,realname",
                "u1", "zhangsan", "张三");
        insert(c, "sys_user", "id,username,realname",
                "u2", "lisi", "李四");
        insert(c, "sys_user", "id,username,realname",
                "u3", "wangwu", "王五");

        Timestamp base = Timestamp.valueOf("2024-01-01 09:00:00");
        insert(c, "ACT_RE_DEPLOYMENT", "ID_,NAME_,DEPLOY_TIME_", "d1", "leave", base);
        insert(c, "ACT_GE_BYTEARRAY", "ID_,NAME_,DEPLOYMENT_ID_,BYTES_",
                "bytes1", "leave.bpmn20.xml", "d1", bytes(BPMN));
        insert(c, "ACT_GE_BYTEARRAY", "ID_,NAME_,DEPLOYMENT_ID_,BYTES_",
                "bytes1svg", "leave.svg", "d1", bytes("<svg xmlns=\"http://www.w3.org/2000/svg\"/>"));
        insert(c, "ACT_RE_PROCDEF",
                "ID_,REV_,NAME_,KEY_,VERSION_,DEPLOYMENT_ID_,RESOURCE_NAME_,DGRM_RESOURCE_NAME_,"
                        + "SUSPENSION_STATE_,TENANT_ID_",
                "leave:1:111", 1, "请假流程", "leave", 1, "d1", "leave.bpmn20.xml", "leave.svg", 1, "t1");

        insert(c, "ACT_GE_BYTEARRAY", "ID_,NAME_,DEPLOYMENT_ID_,BYTES_",
                "bytesDraft", "draft.bpmn20.xml", null, bytes(DRAFT_BPMN));
        insert(c, "ACT_RE_MODEL",
                "ID_,REV_,NAME_,KEY_,CATEGORY_,CREATE_TIME_,LAST_UPDATE_TIME_,VERSION_,"
                        + "EDITOR_SOURCE_VALUE_ID_,TENANT_ID_,META_INFO_",
                "m1", 1, "请假流程草稿", "leaveDraft", "1", base, base, 1, "bytesDraft", "t1",
                "{\"name\":\"请假流程草稿\",\"revision\":1,\"description\":\"草稿描述\"}");

        // p1：运行中，两个并行活动任务
        insert(c, "ACT_HI_PROCINST",
                "ID_,PROC_INST_ID_,BUSINESS_KEY_,PROC_DEF_ID_,START_TIME_,START_USER_ID_,TENANT_ID_",
                "p1", "p1", "leave.BIZ-1", "leave:1:111", base, "u1", "t1");
        insert(c, "act_business_data",
                "ID_,PROC_INST_ID_,BUSINESS_ID_,BUSINESS_KEY_,TASK_ID_,TASK_NAME_,START_USER_ID_,"
                        + "START_TIME_,TITLE_,STATE_,TENANT_ID_,EXT_ID_",
                "b1", "p1", "BIZ-1", "leave.BIZ-1", "t1", "部门审批", "u1",
                base, "张三的请假", "1", "t1", "ext-1");
        insert(c, "ACT_RU_TASK",
                "ID_,NAME_,CREATE_TIME_,OWNER_,ASSIGNEE_,DELEGATION_,PROC_INST_ID_,PROC_DEF_ID_,"
                        + "TASK_DEF_KEY_,PRIORITY_,TENANT_ID_",
                "t1", "部门审批", base, null, "u2", "RESOLVED", "p1", "leave:1:111",
                "deptApprove", 50, "t1");
        insert(c, "ACT_RU_TASK",
                "ID_,NAME_,CREATE_TIME_,OWNER_,ASSIGNEE_,DELEGATION_,PROC_INST_ID_,PROC_DEF_ID_,"
                        + "TASK_DEF_KEY_,PRIORITY_,TENANT_ID_",
                "t2", "人事审批", base, null, "wangwu", "RESOLVED", "p1", "leave:1:111",
                "hrApprove", 50, "t1");
        insert(c, "ACT_RU_IDENTITYLINK", "ID_,TYPE_,USER_ID_,GROUP_ID_,TASK_ID_,PROC_INST_ID_",
                "il1", "candidate", null, "manager", "t2", "p1");
        insert(c, "ACT_RU_EXECUTION",
                "ID_,PROC_INST_ID_,ACT_ID_,IS_ACTIVE_,IS_CONCURRENT_,IS_SCOPE_,PARENT_ID_",
                "e1", "p1", null, 1, 0, 1, null);
        insert(c, "ACT_RU_EXECUTION",
                "ID_,PROC_INST_ID_,ACT_ID_,IS_ACTIVE_,IS_CONCURRENT_,IS_SCOPE_,PARENT_ID_",
                "e2", "p1", "deptApprove", 1, 1, 0, "e1");
        insert(c, "ACT_RU_EXECUTION",
                "ID_,PROC_INST_ID_,ACT_ID_,IS_ACTIVE_,IS_CONCURRENT_,IS_SCOPE_,PARENT_ID_",
                "e3", "p1", "hrApprove", 1, 1, 0, "e1");

        insertVar(c, "hv1", "p1", "days", "integer", 3L, null, null, null, null, base);
        insertVar(c, "hv2", "p1", "reason", "string", null, null, "事假2", null, null, base);
        insertVar(c, "hv3", "p1", "reason", "string", null, null, "事假", null, null,
                Timestamp.valueOf("2024-01-01 10:00:00"));
        insertVar(c, "hv4", "p1", "amount", "double", null, 12.5d, null, null, null, base);
        insertVar(c, "hv5", "p1", "form", "json", null, null, "{\"a\":1}", null, null, base);
        insertVar(c, "hv6", "p1", "blob", "serializable", null, null, null, "ignored", null, base);
        insertVar(c, "hv7", "p1", "localVar", "string", null, null, "local", null, "t1", base);
        // 旧项目 startUserName 变量存的是 username，迁移后应覆盖为 realname
        insertVar(c, "hv8", "p1", "startUserName", "string", null, null, "zhangsan", null, null, base);
        insertVar(c, "hv9", "p1", "submitDate", "date", 1700000000000L, null, null, null, null, base);
        insertRuntimeVar(c, "rv1", "p1", "approved", "boolean", 1L, null, null, null, null);
        insertRuntimeVar(c, "rv2", "p1", "amount", "double", null, 20.5d, null, null, null);

        // 防御性场景：ASSIGNEE_ 出现逗号分隔值，新引擎必须拆成 candidates
        insert(c, "ACT_RU_TASK",
                "ID_,NAME_,CREATE_TIME_,OWNER_,ASSIGNEE_,DELEGATION_,PROC_INST_ID_,PROC_DEF_ID_,"
                        + "TASK_DEF_KEY_,PRIORITY_,TENANT_ID_",
                "t6", "会签审批", base, null, "u2,u3", "RESOLVED", "p1", "leave:1:111",
                "countersign", 50, "t1");

        insert(c, "ACT_HI_TASKINST",
                "ID_,NAME_,PROC_INST_ID_,PROC_DEF_ID_,TASK_DEF_KEY_,ASSIGNEE_,START_TIME_,END_TIME_,"
                        + "DURATION_,DELETE_REASON_,PRIORITY_,FORM_KEY_,TENANT_ID_",
                "t1", "部门审批", "p1", "leave:1:111", "deptApprove", "u2", base, null,
                null, null, 50, null, "t1");
        insert(c, "ACT_HI_TASKINST",
                "ID_,NAME_,PROC_INST_ID_,PROC_DEF_ID_,TASK_DEF_KEY_,ASSIGNEE_,START_TIME_,END_TIME_,"
                        + "DURATION_,DELETE_REASON_,PRIORITY_,FORM_KEY_,TENANT_ID_",
                "t2", "人事审批", "p1", "leave:1:111", "hrApprove", "wangwu", base, null,
                null, null, 50, null, "t1");

        insertActivity(c, "a1", "p1", "start", "开始", "startEvent", base);
        insertActivity(c, "a2", "p1", "deptApprove", "部门审批", "userTask", base);
        insertActivity(c, "a3", "p1", "hrApprove", "人事审批", "userTask", base);

        insert(c, "ACT_HI_COMMENT",
                "ID_,TYPE_,TIME_,USER_ID_,TASK_ID_,PROC_INST_ID_,ACTION_,MESSAGE_,FULL_MSG_,FLOW_STATE_",
                "c1", "comment", Timestamp.valueOf("2024-01-01 11:00:00"), "u2", "t1", "p1",
                "AddComment", "同意", "同意", "1");
        insert(c, "ACT_HI_COMMENT",
                "ID_,TYPE_,TIME_,USER_ID_,TASK_ID_,PROC_INST_ID_,ACTION_,MESSAGE_,FULL_MSG_,FLOW_STATE_",
                "c2", "event", Timestamp.valueOf("2024-01-01 11:01:00"), "u1", "t2", "p1",
                "AddUserLink", null, "linked", null);

        // p2：已完成实例（有业务数据、无运行中任务）
        Timestamp end = Timestamp.valueOf("2024-01-02 18:00:00");
        insert(c, "ACT_HI_PROCINST",
                "ID_,PROC_INST_ID_,BUSINESS_KEY_,PROC_DEF_ID_,START_TIME_,END_TIME_,START_USER_ID_,"
                        + "TENANT_ID_,DELETE_REASON_",
                "p2", "p2", "leave.BIZ-2", "leave:1:111", base, end, "u1", "t1", null);
        insert(c, "act_business_data",
                "ID_,PROC_INST_ID_,BUSINESS_ID_,BUSINESS_KEY_,START_USER_ID_,START_TIME_,TITLE_,STATE_",
                "b2", "p2", "BIZ-2", "leave.BIZ-2", "u1", base, "已完成的请假", "2");
        insert(c, "ACT_HI_TASKINST",
                "ID_,NAME_,PROC_INST_ID_,PROC_DEF_ID_,TASK_DEF_KEY_,ASSIGNEE_,START_TIME_,END_TIME_,"
                        + "DURATION_,DELETE_REASON_,PRIORITY_,FORM_KEY_,TENANT_ID_",
                "t3", "部门审批", "p2", "leave:1:111", "deptApprove", "u2", base, end,
                1000L, null, 50, null, "t1");

        // p3：没有 act_business_data 的历史实例，撤销结束
        insert(c, "ACT_HI_PROCINST",
                "ID_,PROC_INST_ID_,BUSINESS_KEY_,PROC_DEF_ID_,START_TIME_,END_TIME_,START_USER_ID_,"
                        + "TENANT_ID_,DELETE_REASON_",
                "p3", "p3", "leave.BIZ-3", "leave:1:111", base, end, "wangwu", "t1", "撤销");
        insert(c, "ACT_HI_TASKINST",
                "ID_,NAME_,PROC_INST_ID_,PROC_DEF_ID_,TASK_DEF_KEY_,ASSIGNEE_,START_TIME_,END_TIME_,"
                        + "DURATION_,DELETE_REASON_,PRIORITY_,FORM_KEY_,TENANT_ID_",
                "t4", "人事审批", "p3", "leave:1:111", "hrApprove", "u3", base, end,
                2000L, "撤销", 50, null, "t1");

        // p4：旧 terminal() 结束流程后 business_data.STATE_ 仍写 1，只有 flow=0 能识别终止
        insert(c, "ACT_HI_PROCINST",
                "ID_,PROC_INST_ID_,BUSINESS_KEY_,PROC_DEF_ID_,START_TIME_,END_TIME_,START_USER_ID_,"
                        + "TENANT_ID_,DELETE_REASON_",
                "p4", "p4", "leave.BIZ-4", "leave:1:111", base, end, "u1", "t1", null);
        insert(c, "act_business_data",
                "ID_,PROC_INST_ID_,BUSINESS_ID_,BUSINESS_KEY_,START_USER_ID_,START_TIME_,TITLE_,STATE_",
                "b4", "p4", "BIZ-4", "leave.BIZ-4", "u1", base, "被终止的请假", "1");
        insertVar(c, "hv10", "p4", "flow", "long", 0L, null, null, null, null, base);
        insert(c, "ACT_HI_TASKINST",
                "ID_,NAME_,PROC_INST_ID_,PROC_DEF_ID_,TASK_DEF_KEY_,ASSIGNEE_,START_TIME_,END_TIME_,"
                        + "DURATION_,DELETE_REASON_,PRIORITY_,FORM_KEY_,TENANT_ID_",
                "t5", "部门审批", "p4", "leave:1:111", "deptApprove", "u2", base, end,
                3000L, "终止", 50, null, "t1");
    }

    private static void insertActivity(Connection c, String id, String instId, String actId, String name,
                                       String type, Timestamp start) throws SQLException {
        insert(c, "ACT_HI_ACTINST",
                "ID_,PROC_INST_ID_,PROC_DEF_ID_,ACT_ID_,ACT_NAME_,ACT_TYPE_,START_TIME_,TENANT_ID_",
                id, instId, "leave:1:111", actId, name, type, start, "t1");
    }

    private static void insertVar(Connection c, String id, String instId, String name, String type,
                                  Long longValue, Double doubleValue, String text, String text2,
                                  String taskId, Timestamp updated) throws SQLException {
        insert(c, "ACT_HI_VARINST",
                "ID_,PROC_INST_ID_,NAME_,VAR_TYPE_,LONG_,DOUBLE_,TEXT_,TEXT2_,TASK_ID_,"
                        + "CREATE_TIME_,LAST_UPDATED_TIME_",
                id, instId, name, type, longValue, doubleValue, text, text2, taskId, updated, updated);
    }

    private static void insertRuntimeVar(Connection c, String id, String instId, String name, String type,
                                         Long longValue, Double doubleValue, String text, String text2,
                                         String taskId) throws SQLException {
        insert(c, "ACT_RU_VARIABLE",
                "ID_,PROC_INST_ID_,NAME_,TYPE_,LONG_,DOUBLE_,TEXT_,TEXT2_,TASK_ID_",
                id, instId, name, type, longValue, doubleValue, text, text2, taskId);
    }

    // ------------------------------------------------------------------
    // H2 建表
    // ------------------------------------------------------------------

    private static void createActivitiSchema(Connection c) throws SQLException {
        exec(c, "CREATE TABLE sys_user (id VARCHAR(64) PRIMARY KEY, username VARCHAR(64), realname VARCHAR(100))");
        exec(c, "CREATE TABLE ACT_RE_DEPLOYMENT (ID_ VARCHAR(64) PRIMARY KEY, NAME_ VARCHAR(255), DEPLOY_TIME_ TIMESTAMP)");
        exec(c, "CREATE TABLE ACT_GE_BYTEARRAY (ID_ VARCHAR(64) PRIMARY KEY, NAME_ VARCHAR(255), "
                + "DEPLOYMENT_ID_ VARCHAR(64), BYTES_ VARBINARY(100000))");
        exec(c, "CREATE TABLE ACT_RE_PROCDEF (ID_ VARCHAR(64) PRIMARY KEY, REV_ INT, NAME_ VARCHAR(255), "
                + "KEY_ VARCHAR(255), VERSION_ INT, DEPLOYMENT_ID_ VARCHAR(64), RESOURCE_NAME_ VARCHAR(255), "
                + "DGRM_RESOURCE_NAME_ VARCHAR(255), SUSPENSION_STATE_ INT, TENANT_ID_ VARCHAR(64))");
        exec(c, "CREATE TABLE ACT_RE_MODEL (ID_ VARCHAR(64) PRIMARY KEY, REV_ INT, NAME_ VARCHAR(255), "
                + "KEY_ VARCHAR(255), CATEGORY_ VARCHAR(255), CREATE_TIME_ TIMESTAMP, LAST_UPDATE_TIME_ TIMESTAMP, "
                + "VERSION_ INT, EDITOR_SOURCE_VALUE_ID_ VARCHAR(64), TENANT_ID_ VARCHAR(64), META_INFO_ VARCHAR(2000))");
        exec(c, "CREATE TABLE ACT_HI_PROCINST (ID_ VARCHAR(64) PRIMARY KEY, PROC_INST_ID_ VARCHAR(64), "
                + "BUSINESS_KEY_ VARCHAR(255), PROC_DEF_ID_ VARCHAR(64), START_TIME_ TIMESTAMP, END_TIME_ TIMESTAMP, "
                + "START_USER_ID_ VARCHAR(64), DELETE_REASON_ VARCHAR(500), TENANT_ID_ VARCHAR(64), NAME_ VARCHAR(255))");
        exec(c, "CREATE TABLE act_business_data (ID_ VARCHAR(64) PRIMARY KEY, PROC_INST_ID_ VARCHAR(64), "
                + "BUSINESS_ID_ VARCHAR(100), BUSINESS_KEY_ VARCHAR(200), TASK_ID_ VARCHAR(64), "
                + "TASK_NAME_ VARCHAR(255), START_USER_ID_ VARCHAR(64), START_TIME_ TIMESTAMP, TITLE_ VARCHAR(500), "
                + "STATE_ VARCHAR(10), TENANT_ID_ VARCHAR(64), EXT_ID_ VARCHAR(100))");
        exec(c, "CREATE TABLE ACT_HI_TASKINST (ID_ VARCHAR(64) PRIMARY KEY, NAME_ VARCHAR(255), "
                + "PROC_INST_ID_ VARCHAR(64), PROC_DEF_ID_ VARCHAR(64), TASK_DEF_KEY_ VARCHAR(255), "
                + "ASSIGNEE_ VARCHAR(64), OWNER_ VARCHAR(64), START_TIME_ TIMESTAMP, CLAIM_TIME_ TIMESTAMP, "
                + "END_TIME_ TIMESTAMP, DURATION_ BIGINT, DELETE_REASON_ VARCHAR(500), PRIORITY_ INT, "
                + "FORM_KEY_ VARCHAR(255), PARENT_TASK_ID_ VARCHAR(64), TENANT_ID_ VARCHAR(64))");
        exec(c, "CREATE TABLE ACT_RU_TASK (ID_ VARCHAR(64) PRIMARY KEY, NAME_ VARCHAR(255), "
                + "CREATE_TIME_ TIMESTAMP, OWNER_ VARCHAR(64), ASSIGNEE_ VARCHAR(64), DELEGATION_ VARCHAR(20), "
                + "PROC_INST_ID_ VARCHAR(64), PROC_DEF_ID_ VARCHAR(64), TASK_DEF_KEY_ VARCHAR(255), "
                + "PRIORITY_ INT, PARENT_TASK_ID_ VARCHAR(64), FORM_KEY_ VARCHAR(255), TENANT_ID_ VARCHAR(64))");
        exec(c, "CREATE TABLE ACT_RU_IDENTITYLINK (ID_ VARCHAR(64) PRIMARY KEY, TYPE_ VARCHAR(20), "
                + "USER_ID_ VARCHAR(64), GROUP_ID_ VARCHAR(64), TASK_ID_ VARCHAR(64), PROC_INST_ID_ VARCHAR(64))");
        exec(c, "CREATE TABLE ACT_RU_EXECUTION (ID_ VARCHAR(64) PRIMARY KEY, PROC_INST_ID_ VARCHAR(64), "
                + "ACT_ID_ VARCHAR(255), IS_ACTIVE_ INT, IS_CONCURRENT_ INT, IS_SCOPE_ INT, PARENT_ID_ VARCHAR(64))");
        exec(c, "CREATE TABLE ACT_HI_VARINST (ID_ VARCHAR(64) PRIMARY KEY, PROC_INST_ID_ VARCHAR(64), "
                + "NAME_ VARCHAR(255), VAR_TYPE_ VARCHAR(64), LONG_ BIGINT, DOUBLE_ DOUBLE, TEXT_ VARCHAR(4000), "
                + "TEXT2_ VARCHAR(4000), TASK_ID_ VARCHAR(64), CREATE_TIME_ TIMESTAMP, LAST_UPDATED_TIME_ TIMESTAMP)");
        exec(c, "CREATE TABLE ACT_RU_VARIABLE (ID_ VARCHAR(64) PRIMARY KEY, PROC_INST_ID_ VARCHAR(64), "
                + "NAME_ VARCHAR(255), TYPE_ VARCHAR(64), LONG_ BIGINT, DOUBLE_ DOUBLE, TEXT_ VARCHAR(4000), "
                + "TEXT2_ VARCHAR(4000), TASK_ID_ VARCHAR(64))");
        exec(c, "CREATE TABLE ACT_HI_ACTINST (ID_ VARCHAR(64) PRIMARY KEY, PROC_INST_ID_ VARCHAR(64), "
                + "PROC_DEF_ID_ VARCHAR(64), ACT_ID_ VARCHAR(255), ACT_NAME_ VARCHAR(255), "
                + "ACT_TYPE_ VARCHAR(64), START_TIME_ TIMESTAMP, TENANT_ID_ VARCHAR(64))");
        exec(c, "CREATE TABLE ACT_HI_COMMENT (ID_ VARCHAR(64) PRIMARY KEY, TYPE_ VARCHAR(20), TIME_ TIMESTAMP, "
                + "USER_ID_ VARCHAR(64), TASK_ID_ VARCHAR(64), PROC_INST_ID_ VARCHAR(64), ACTION_ VARCHAR(64), "
                + "MESSAGE_ VARCHAR(4000), FULL_MSG_ VARCHAR(4000), FLOW_STATE_ VARCHAR(10))");
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static String runMigration(String sourceUrl, String targetUrl, String mode, boolean truncate) {
        return runMigration(sourceUrl, targetUrl, mode, truncate, null);
    }

    private static String runMigration(String sourceUrl, String targetUrl, String mode, boolean truncate,
                                       String targetDialect) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out;
        try {
            out = new PrintStream(buffer, true, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        java.util.List<String> args = new java.util.ArrayList<String>();
        args.add("--source-url");
        args.add(sourceUrl);
        args.add("--source-user");
        args.add("sa");
        args.add("--source-password");
        args.add("");
        args.add("--target-url");
        args.add(targetUrl);
        args.add("--target-user");
        args.add("sa");
        args.add("--target-password");
        args.add("");
        args.add("--mode");
        args.add(mode);
        args.add("--truncate");
        args.add(String.valueOf(truncate));
        args.add("--create-schema");
        args.add("true");
        if (targetDialect != null) {
            args.add("--target-dialect");
            args.add(targetDialect);
        }
        int code = MigrationMain.run(args.toArray(new String[0]), out, out);
        out.flush();
        String text = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        assertEquals(0, code, "迁移应成功，输出:\n" + text);
        return text;
    }

    private static void exec(Connection c, String sql) throws SQLException {
        Statement statement = c.createStatement();
        try {
            statement.execute(sql);
        } finally {
            statement.close();
        }
    }

    private static void insert(Connection c, String table, String columns, Object... values) throws SQLException {
        String[] names = columns.split(",");
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" (");
        StringBuilder marks = new StringBuilder(" VALUES (");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                sql.append(", ");
                marks.append(", ");
            }
            sql.append(names[i].trim());
            marks.append('?');
        }
        sql.append(')').append(marks).append(')');
        PreparedStatement statement = c.prepareStatement(sql.toString());
        try {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            statement.executeUpdate();
        } finally {
            statement.close();
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        ResultSet rs = c.getMetaData().getTables(null, null, "%", new String[]{"TABLE"});
        try {
            while (rs.next()) {
                if (table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        } finally {
            rs.close();
        }
        return false;
    }

    private static long count(Connection c, String table) throws SQLException {
        return number(c, "SELECT COUNT(*) FROM " + table);
    }

    private static long number(Connection c, String sql) throws SQLException {
        Statement statement = c.createStatement();
        try {
            ResultSet rs = statement.executeQuery(sql);
            try {
                return rs.next() ? rs.getLong(1) : 0L;
            } finally {
                rs.close();
            }
        } finally {
            statement.close();
        }
    }

    private static String text(Connection c, String sql) throws SQLException {
        Statement statement = c.createStatement();
        try {
            ResultSet rs = statement.executeQuery(sql);
            try {
                return rs.next() ? rs.getString(1) : null;
            } finally {
                rs.close();
            }
        } finally {
            statement.close();
        }
    }

    private static JsonNode tokensOf(Connection c, String instanceId) throws Exception {
        String json = text(c, "SELECT active_tokens FROM flow_inst WHERE id = '" + instanceId + "'");
        assertNotNull(json);
        return Json.MAPPER.readTree(json);
    }

    private static Timestamp now() {
        return new Timestamp(System.currentTimeMillis());
    }

    private static String resource(String name) throws IOException {
        InputStream in = MigrationH2Test.class.getResourceAsStream(name);
        assertNotNull(in, "找不到测试资源 " + name);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
