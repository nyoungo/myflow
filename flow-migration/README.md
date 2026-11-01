# flow-migration

把旧项目 `joinsoft-boot-module-flow`（Activiti 6.0.0）的业务数据迁移到 `flow-approval`（自研 BPMN 引擎）的独立工具。

- 纯 JDBC + `DatabaseMetaData` 读写，**表名/列名大小写无关**
- 支持 **MySQL 5.7/8.0、PostgreSQL 12+、瀚高 HighGo**（瀚高走 PostgreSQL 协议）
- 默认 `dry-run` 只统计不写入；可重复执行，按主键覆盖
- 只引入 Activiti 的 BPMN 模型/转换器（用于把草稿 JSON 转成 XML），**不引入流程引擎运行时**；旧库 `ACT_*` 表**只读不删**

## 1. 迁移范围

| 旧库 | 新库 | 说明 |
| --- | --- | --- |
| `ACT_RE_PROCDEF` | `flow_def` | 已发布流程定义，BPMN/SVG 取自 `ACT_GE_BYTEARRAY` |
| `ACT_RE_MODEL` | `flow_def` | 草稿模型（`version=0`）。编辑器源是 Activiti 设计器 JSON 时，用 Activiti 转换器转成 BPMN XML；转换失败才退化为部署快照，仍失败则跳过并告警 |
| `ACT_HI_PROCINST` + `act_business_data` | `flow_inst` | 运行中/已完成/已终止/已撤销实例 |
| `ACT_HI_VARINST` + `ACT_RU_VARIABLE` | `flow_inst.variables` | 还原为 JSON，保留数值/布尔/JSON 类型 |
| `ACT_HI_ACTINST` | `flow_inst.activities` | 流程图历史高亮 |
| `ACT_RU_TASK` + `ACT_RU_EXECUTION` | `flow_inst.active_tokens` | 运行中令牌，保证迁移后可继续审批 |
| `ACT_HI_TASKINST` + `ACT_RU_TASK` | `flow_task` | 已办历史 + 当前待办 |
| `ACT_RU_IDENTITYLINK` | `flow_task.candidate_users/groups` | 候选用户、候选角色 |
| `ACT_HI_COMMENT` | `flow_comment` | 审批意见，保留旧 `FLOW_STATE_` |

旧库的 `ACT_*`、`act_business_data` 不会被修改。

## 2. 构建

需要 JDK 8 + Maven 3.6+：

```bash
cd flow-migration
mvn clean package
# 产物：target/flow-migration.jar（含 MySQL/PostgreSQL 驱动和 Activiti JSON→BPMN 转换器）
```

测试使用 H2 模拟 Activiti 6 表和新库表，覆盖 dry-run、并行运行实例、已完成实例、无业务数据实例、变量类型、意见、幂等重跑，以及**设计器 JSON 草稿转 BPMN XML**（含 `userFilter`/`autoPass` 等扩展属性）：

```bash
mvn test
```

## 3. 快速开始

### 3.1 先演练（推荐）

```bash
java -jar flow-migration.jar \
  --source-url "jdbc:mysql://127.0.0.1:3306/old_flow?useUnicode=true&characterEncoding=UTF-8" \
  --source-user root --source-password 123456 \
  --target-url "jdbc:postgresql://127.0.0.1:5432/new_flow" \
  --target-user postgres --target-password 123456 \
  --mode dry-run --report ./migration-report.csv
```

确认输出中的读取/写入/跳过数量和告警后再正式执行。

### 3.2 正式执行

```bash
java -jar flow-migration.jar \
  --source-url "jdbc:mysql://127.0.0.1:3306/old_flow" --source-user root --source-password 123456 \
  --target-url "jdbc:postgresql://127.0.0.1:5432/new_flow" --target-user postgres --target-password 123456 \
  --mode execute --report ./migration-report.csv
```

`execute` 默认会自动创建 `flow_def/flow_inst/flow_task/flow_comment` 及索引；若目标库由 DBA 单独建表，加 `--create-schema false`。

需要清空目标表重来：

```bash
... --mode execute --truncate true
```

## 4. 三种数据库示例

### MySQL → PostgreSQL

```bash
java -jar flow-migration.jar \
  --source-url "jdbc:mysql://old:3306/flow" --source-user root --source-password pwd \
  --target-url "jdbc:postgresql://new:5432/flow" --target-user postgres --target-password pwd \
  --mode execute
```

### MySQL → 瀚高 HighGo

瀚高默认兼容 PostgreSQL 协议；如使用瀚高官方驱动，显式指定驱动类：

```bash
java -jar flow-migration.jar \
  --source-url "jdbc:mysql://old:3306/flow" --source-user root --source-password pwd \
  --target-url "jdbc:highgo://new:5866/flow" --target-user highgo --target-password pwd \
  --target-driver com.highgo.jdbc.Driver \
  --mode execute
```

`jdbc:highgo:` 会被识别为 PostgreSQL 方言；如果现场把驱动注册成了 `org.postgresql.Driver`，可以省略 `--target-driver`。

### PostgreSQL / 瀚高 → MySQL

```bash
java -jar flow-migration.jar \
  --source-url "jdbc:postgresql://old:5432/flow" --source-user postgres --source-password pwd \
  --target-url "jdbc:mysql://new:3306/flow" --target-user root --target-password pwd \
  --mode execute
```

旧库如果是瀚高，把 `--source-url` 改成 `jdbc:highgo://...`，必要时加 `--source-driver com.highgo.jdbc.Driver`。

## 5. 参数说明

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `--source-url` | 必填 | 旧库 JDBC URL |
| `--source-user` / `--source-password` | 空 | 旧库账号 |
| `--source-schema` | 当前 schema | PostgreSQL/瀚高的 schema；MySQL 下等价于切库 |
| `--source-driver` | 自动 | 驱动类，如 `com.highgo.jdbc.Driver` |
| `--source-dialect` | 按 URL 推断 | `MYSQL` / `POSTGRESQL` / `H2` |
| `--target-url` / `--target-user` / `--target-password` | 必填 | 新库连接 |
| `--target-schema` / `--target-driver` / `--target-dialect` | 同上 | 新库 |
| `--mode` | `dry-run` | `dry-run` 只统计；`execute` 写入 |
| `--tables` | `def,inst,task,comment` | 迁移范围；`task`/`comment` 必须同时含 `inst` |
| `--include-drafts` | `true` | 是否迁移 `ACT_RE_MODEL` 草稿 |
| `--truncate` | `false` | `execute` 前清空所选目标表 |
| `--create-schema` | `true` | `execute` 时自动建表 |
| `--assignee-format` | `auto` | 旧审批人值是用户 ID 还是用户名：`auto`/`id`/`username` |
| `--page-size` | `200` | 源库分页大小 |
| `--batch-size` | `200` | 目标库批量写入大小 |
| `--skip-unsupported-instances` | `true` | 运行中实例含新引擎不支持的节点/缺 BPMN 时跳过 |
| `--report` | 无 | 输出逐行告警 CSV（UTF-8 BOM） |
| `--max-warnings` | `2000` | 内存中最多保留告警数 |
| `--config` | 无 | properties 配置文件，命令行优先 |

配置文件示例：

```properties
source-url=jdbc:mysql://old:3306/flow
source-user=root
source-password=123456
target-url=jdbc:postgresql://new:5432/flow
target-user=postgres
target-password=123456
mode=execute
report=./migration-report.csv
```

## 6. 数据映射细节

### 流程定义

- 已发布定义：`ACT_RE_PROCDEF` → `flow_def`（`version=1..n`，`status=PUBLISHED/SUSPENDED`），BPMN/SVG 取自部署资源。
- 草稿模型：`ACT_RE_MODEL` → `flow_def`（`version=0`，`status=DRAFT`）。编辑器源处理顺序：
  1. 本身是 BPMN XML：直接使用；
  2. 是 Activiti 设计器 JSON：用 Activiti 6 的 `BpmnJsonConverter` 转 `BpmnModel`，再用 `BpmnXMLConverter` 生成 XML；
  3. 转换失败：退化为 `ACT_GE_BYTEARRAY` 中的部署快照；
  4. 仍不可用：跳过并在报告中告警。
- 设计器 JSON 的自定义属性（`userFilter`、`approveButton`、`approveButtonNames`、`autoPass`、`noticeStartUser`、`noticeApprovalUser`）由迁移工程内复制的 `CustomUserTaskJsonConverter` 保留，写成 BPMN 扩展元素，新引擎 `BpmnXmlParser` 可直接读取。
- `flow_def.meta_info` 写成新引擎格式 `{"name":...,"description":...,"revision":...}`；草稿的 `description` 优先取旧 `ACT_RE_MODEL.META_INFO_` 中的同名字段。

### 用户

旧库 `sys_user`：

- `auto`：值能对上 `id` 就按 ID，否则按 `username` 换成 ID
- `id`：原样保留
- `username`：一律按用户名换成 ID

`flow_inst.start_user_name`、`flow_comment.user_name` 从 `sys_user.realname` 补齐；找不到时保留原值并告警。

### 任务办理人

- 旧项目把多人办理写成 `ACT_RU_IDENTITYLINK` 的 candidate users（`setTaskUser`/`setNextNodePsnHandler` 都会先 `setAssignee(null)` 再 `addCandidateUser`），因此正常数据里 `ASSIGNEE_` 是单值，迁移后 `assignee` 直接对应。
- 防御性处理：若 `ASSIGNEE_` 出现逗号分隔值（人工改库或旧版本遗留），运行中任务会转为 `assignee=null` + `candidate_users`；已结束任务保留第一个并告警。
- 候选组来自 `ACT_RU_IDENTITYLINK.GROUP_ID_`。旧项目按 `sys_role.role_code` 解释组值，迁移时原样写入 `candidate_groups`；但旧代码在重新分配审批人时会删除组链接并换成 candidate users，所以迁移后组大多为空。

### 实例状态

| 新状态 | 含义 | 判定 |
| --- | --- | --- |
| `1` | 运行中 | `act_business_data.STATE_=1` 且 `ACT_HI_PROCINST.END_TIME_` 为空 |
| `2` | 已完成 | 旧状态为 2，或历史实例正常结束 |
| `3` | 已终止 | 旧 `terminal()` 结束流程但 `STATE_` 仍写 1，因此以「已结束 + 流程变量 `flow=0`」识别；删除原因含“终止/terminate”时也归为 3 |
| `4` | 已撤销 | 删除原因含“撤销/cancel/repeal”。旧 `repeal()` 会直接删除历史实例，因此状态 4 通常不会出现在源库中 |

> `ACT_HI_PROCINST.DELETE_REASON_` 由 `deleteProcessInstance` 写入，正常完成和 `terminal()` 都可能为空，不能单独作为终止判据。

### 令牌

- 每个 `ACT_RU_TASK` → `{"id":"T<taskId>","node":"<TASK_DEF_KEY_>"}`
- 其他活动执行流 → `{"id":"E<execId>","node":"<ACT_ID_>"}`
- `flow_task.token_id` 与上面的 `id` 对齐，迁移后打开待办可直接审批

### 变量

- `long/integer/short/byte/date` → JSON 数字（`date` 为毫秒时间戳）
- `double/float/bigdecimal` → JSON 数字
- `boolean` → JSON `true/false`
- `json` → JSON 对象/数组
- `null` → `null`
- `serializable/bytes` → 跳过并告警（无法跨引擎反序列化）

> Activiti 6 的历史变量表列名是 `ACT_HI_VARINST.VAR_TYPE_`，运行时表是 `ACT_RU_VARIABLE.TYPE_`，工具会按实际列名自动识别。
> 旧项目在流程变量里保存的 `startUserName` 是 **username**，新引擎使用 realname，迁移时会按 `sys_user` 覆盖为姓名。

### 意见类型

`ACT_HI_COMMENT.TYPE_/ACTION_` 与文本推断为：

`comment` / `back` / `jump` / `cancel` / `terminal` / `system`

旧项目自定义的 `FLOW_STATE_` 原样写入 `flow_comment.flow_state`。

## 7. 迁移后校验

工具在 `execute` 结束时会自动检查并告警：

- `flow_task.inst_id` 找不到对应实例
- `flow_comment.inst_id` 找不到对应实例
- `flow_inst.def_id` 找不到对应定义
- `business_key` 重复
- 状态为运行中但没有 `ACTIVE` 任务

建议人工再跑一遍：

```sql
-- 数据量对比
SELECT COUNT(*) FROM flow_inst;
SELECT COUNT(*) FROM flow_task WHERE status = 'ACTIVE';
SELECT COUNT(*) FROM flow_comment;

-- 运行中实例必须有活动任务
SELECT i.id, i.business_key
FROM flow_inst i
WHERE i.state = '1'
  AND NOT EXISTS (SELECT 1 FROM flow_task t
                  WHERE t.inst_id = i.id AND t.status = 'ACTIVE');

-- 令牌与任务必须对得上
SELECT t.id, t.token_id FROM flow_task t
WHERE t.status = 'ACTIVE' AND t.token_id IS NULL;
```

然后启动 `flow-approval`，重点抽查：

1. 待办列表能查到迁移过来的运行中任务
2. 打开详情流程图能高亮历史节点
3. 审批一条迁移过来的任务，流程能继续流转
4. 已办、我发起的、审批记录能显示旧数据

## 8. 已知限制

1. **BPMN 引擎差异**：`subProcess`、`callActivity`、边界事件、多实例、定时/消息/信号事件等新引擎不支持的结构，其运行中实例默认跳过（定义仍会迁移）。已结束实例照常迁移历史。
2. `serviceTask/scriptTask/sendTask/receiveTask/businessRuleTask/manualTask` 只告警：新引擎会把它们当空节点直接跳过，审批路径可能变化。
3. 草稿迁移依赖 Activiti 6 的 JSON 转换器，能覆盖设计器保存的标准 BPMN 元素与旧项目 6 个自定义 UserTask 属性；如果旧库里的 JSON 由被魔改过的设计器生成、包含转换器不认识的 stencil，可能转换失败并退化为部署快照（无快照则跳过并告警）。转换后的草稿请在新设计器中复核后再发布。
4. `serializable` / `bytes` 类型变量无法迁移。
5. 旧库候选组值按 `role_code` 使用（`setNextNodePsnHandler` 用 `role_code in (...)` 查角色），迁移时原样写入 `candidate_groups`；如果现场存的是角色 ID，需要另做角色 ID → `role_code` 映射。
6. 流程变量里如果引用了旧系统的类/服务（如 `${someSpringBean}`），新引擎没有同名 Bean，审批时表达式会失败，需要改造流程定义。
7. `start_depart` 语义不同：旧库变量存的是用 `,` 拼接的部门**名称**（`departIdModels...getTitle()`），新引擎 `flow_inst.start_depart` 存 `sys_user.dept_id`。工具原样迁移旧值；若新系统按部门 ID 过滤，需要另行映射。
8. 本工具不迁移租户、权限、菜单等业务表，只处理流程相关的 `ACT_*` → `flow_*`。
9. 未在真实 MySQL/PostgreSQL/瀚高实例上做自动化回归（开发环境无这些服务），但 SQL 全部使用标准 JDBC + 方言 DDL，H2 端到端测试覆盖了完整链路。**上线前请务必在预发库跑一次 dry-run + execute 并抽查。**

## 9. 重跑与回滚

- 工具按主键 `DELETE` + `INSERT`，同一份源数据重复执行结果一致，可随时重跑。
- 旧库 `ACT_*` 始终保留，迁移失败不影响旧系统。
- 回滚新库：

```sql
DELETE FROM flow_comment;
DELETE FROM flow_task;
DELETE FROM flow_inst;
DELETE FROM flow_def;
```

或切换前直接备份/还原新库。

## 10. 工程结构

```
src/main/java/com/joinsoft/flow/migration/
  MigrationMain.java        命令行入口 / 帮助
  Migrator.java             连接、建表、编排、目标库校验
  Options.java              参数解析
  Dialect.java              方言与建表脚本选择
  Db.java                   JDBC 连接 + 大小写无关表/列解析
  DefinitionMigrator.java   ACT_RE_PROCDEF/ACT_RE_MODEL -> flow_def
  InstanceMigrator.java     实例/变量/令牌/活动 -> flow_inst
  TaskMigrator.java         任务/候选人 -> flow_task
  CommentMigrator.java      审批意见 -> flow_comment
  UserResolver.java         用户 ID/用户名/姓名映射
  Writer.java               幂等批量写入
  Report.java               计数/告警/CSV
  editor/
    DraftBpmnConverter.java           设计器 JSON -> BPMN XML
    CustomUserTaskJsonConverter.java  保留 userFilter/autoPass 等自定义属性
    BpmnJsonConverterProperties.java  暴露 Activiti 转换器注册表
src/main/resources/schema/  mysql.sql / postgresql.sql / h2.sql
src/test/java/...           H2 端到端测试
src/test/resources/         设计器 JSON 测试夹具
```
