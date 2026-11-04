# flow-migration

把旧项目 `joinsoft-boot-module-flow`（Activiti 6.0.0）的业务数据迁移到 `flow-approval`（自研 BPMN 引擎）的独立工具。

- 纯 JDBC + `DatabaseMetaData` 读写，**表名/列名大小写无关**
- 支持 **MySQL 5.7/8.0、PostgreSQL 12+、瀚高 HighGo**（瀚高走 PostgreSQL 协议）
- 默认 `dry-run` 只统计不写入；可重复执行，按主键覆盖
- 写入前预检旧库 `ACT_*` 表与目标表列，缺列/缺表直接给出清单，不会跑到一半才报错
- 按「定义 → 实例 → 任务 → 意见」分阶段提交，中断后可直接重跑
- 只引入 Activiti 的 BPMN 模型/转换器（用于把草稿 JSON 转成 XML），**不引入流程引擎运行时**；旧库 `ACT_*` 表**只读不删**

## 1. 迁移范围

| 旧库 | 新库 | 说明 |
| --- | --- | --- |
| `ACT_RE_PROCDEF` | `flow_def` | 已发布流程定义，BPMN/SVG 取自 `ACT_GE_BYTEARRAY` |
| `ACT_RE_MODEL` | `flow_def` | 草稿模型（`version=0`）。编辑器源是 Activiti 设计器 JSON 时，用 Activiti 转换器转成 BPMN XML；转换失败才退化为部署快照，仍失败则跳过并告警 |
| `ACT_HI_PROCINST` + `act_business_data` | `flow_inst` | 运行中/已完成/已终止实例；旧「已撤销」按已终止迁移 |
| `ACT_HI_VARINST` + `ACT_RU_VARIABLE` | `flow_inst.variables` | 还原为 JSON，保留数值/布尔/JSON 类型 |
| `ACT_HI_ACTINST` | `flow_inst.activities` | 流程图历史高亮 |
| `ACT_RU_TASK` + `ACT_RU_EXECUTION` | `flow_inst.active_tokens` | 运行中令牌，保证迁移后可继续审批 |
| `ACT_HI_TASKINST` + `ACT_RU_TASK` | `flow_task` | 已办历史 + 当前待办 |
| `ACT_RU_IDENTITYLINK` | `flow_task.candidate_users/groups` | 候选用户、候选角色 |
| `ACT_HI_COMMENT` | `flow_comment` | 审批意见，保留旧 `FLOW_STATE_`；`tenant_id` 取旧列或继承实例租户 |

旧库的 `ACT_*`、`act_business_data` 不会被修改。

## 2. 构建

需要 JDK 8 + Maven 3.6+：

```bash
cd flow-migration
mvn clean package
# 产物：target/flow-migration.jar（内置 MySQL / PostgreSQL / 瀚高驱动和 Activiti JSON→BPMN 转换器）
java -jar target/flow-migration.jar --version   # flow-migration 1.1.1
```

测试使用 H2 模拟 Activiti 6 表和新库表，共 19 个用例：

- `MigrationH2Test`（5）：端到端，覆盖 dry-run、并行运行实例、已完成实例、无业务数据实例、变量类型、意见、幂等重跑、`--fail-on-warnings` 退出码、意见租户回退、**设计器 JSON 草稿转 BPMN XML**（含 `userFilter`/`autoPass` 等扩展属性）；
- `OptionsTest`（5）：参数默认值、配置文件优先级、非法 `--tables`、`--version`；
- `DialectTest`（4）：建表脚本切分（注释/引号内的分号）、方言推断；
- `ReportTest`（2）：阶段汇总、告警截断统计、CSV 转义；
- `WriterTest`（1）：同主键覆盖、去重集合滚动清理、NULL 绑定；
- `DbTest`（2）：URL → 驱动类推断、瀚高驱动已在 classpath。

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

写入前会做两轮预检：

1. 旧库：列出检测到的 `ACT_*` 表，缺少 `--tables` 必需的 `ACT_RE_PROCDEF` / `ACT_HI_PROCINST` 时直接失败；其余表缺失只提示。
2. 新库：按工具写入的列清单比对四张表，缺列时给出完整清单并提示先执行最新版 `flow-schema.sql`（或先启动一次应用触发 `FlowSchemaInitializer`），不会等到 INSERT 才报错。

需要清空目标表重来：

```bash
... --mode execute --truncate true
```

迁移按「定义 → 实例 → 任务 → 意见」分阶段提交，每个阶段结束即落库。中途失败不会回滚已完成阶段，修好问题后直接重跑即可（按主键覆盖，结果一致）。

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
| `--tables` | `def,inst,task,comment` | 迁移范围；`task`/`comment` 必须同时含 `inst`；非法值直接报错 |
| `--include-drafts` | `true` | 是否迁移 `ACT_RE_MODEL` 草稿 |
| `--truncate` | `false` | `execute` 前清空所选目标表 |
| `--create-schema` | `true` | `execute` 时自动建表 |
| `--assignee-format` | `auto` | 旧审批人值是用户 ID 还是用户名：`auto`/`id`/`username` |
| `--page-size` | `200` | 源库分页大小 |
| `--batch-size` | `200` | 目标库批量写入大小 |
| `--skip-unsupported-instances` | `true` | 运行中实例含新引擎不支持的节点/缺 BPMN 时跳过 |
| `--report` | 无 | 输出迁移报告 CSV：阶段汇总 + 逐行告警（UTF-8 BOM）；失败时也会写出已产生的告警 |
| `--max-warnings` | `2000` | 内存中最多保留告警数，超出仅计数 |
| `--fail-on-warnings` | `false` | 有告警时以退出码 `4` 结束，便于 CI/脚本判定 |
| `--config` | 无 | properties 配置文件，命令行优先 |
| `-h, --help` | — | 显示帮助 |
| `--version` / `-V` | — | 显示版本号 |

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

退出码：

| 退出码 | 含义 |
| --- | --- |
| `0` | 成功 |
| `2` | 参数错误（会同时打印帮助） |
| `3` | 迁移失败（连接、建表、写入、校验异常） |
| `4` | 迁移成功但存在告警，且开启了 `--fail-on-warnings true` |

> `--max-warnings` 达到上限后只累计数量，不丢弃计数；报告与终端汇总里的告警数包含被截断的部分。

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

旧库里的「撤销」如何处理：

- 旧 `repeal()` 会直接删除历史实例，正常数据里很少残留；若 `STATE_` 或 `DELETE_REASON_` 仍留有撤销痕迹，**统一映射为新引擎的 `3`（已终止）**。
- 原因：新引擎的撤销是物理删除实例（等同未提交），没有 `state=4` 这个状态。若照旧写成 4，实例在所有列表（待办/已办/我发起的/审批记录）里都不可见，等于数据丢失。
- 迁移后的撤销实例仍可在已办、我发起的里追溯，只是状态显示为已终止。

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

`TYPE_='event'` 的记录（`AddUserLink` / `DeleteGroupLink` 等候选人变更）不是审批意见，旧系统列表同样只查 `TYPE_='comment'`，因此直接跳过；报告里汇总为一条告警，不逐行刷屏。

### 租户

四张 `flow_*` 表都有 `tenant_id`，映射规则：

| 目标列 | 取值 |
| --- | --- |
| `flow_def.tenant_id` | `ACT_RE_PROCDEF.TENANT_ID_` / `ACT_RE_MODEL.TENANT_ID_` |
| `flow_inst.tenant_id` | 优先 `act_business_data.TENANT_ID_`，缺失时取 `ACT_HI_PROCINST.TENANT_ID_` |
| `flow_task.tenant_id` | `ACT_HI_TASKINST.TENANT_ID_` / `ACT_RU_TASK.TENANT_ID_` |
| `flow_comment.tenant_id` | 优先 `ACT_HI_COMMENT.TENANT_ID_`，缺失时继承所属实例的 `tenant_id` |

旧表没有 `TENANT_ID_` 列时按 `NULL` 处理，不中断迁移。多租户部署下若旧库本身不分租户，迁移后数据属主租户为 `NULL`，需要按现场规则补一个默认租户值。

## 7. 迁移后校验

工具在 `execute` 结束时会自动检查并告警：

- `flow_task.inst_id` 找不到对应实例
- `flow_comment.inst_id` 找不到对应实例
- `flow_inst.def_id` 找不到对应定义
- `business_key` 重复
- 状态为运行中但没有 `ACTIVE` 任务
- 执行成功后尝试补建 `uk_flow_inst_business_key` 唯一索引；若 `business_key` 有重复，只告警不建索引，并给出可直接执行的建索引 SQL

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
8. 本工具只迁移流程相关的 `ACT_*` → `flow_*`，不迁移权限、菜单、业务表。四张 `flow_*` 表的 `tenant_id` 会随数据带过去（规则见「租户」），但租户本身、用户/角色/部门等主数据仍需由 `joinsoft-code` 侧保证一致。
9. 未在真实 MySQL/PostgreSQL/瀚高实例上做自动化回归（开发环境无这些服务），但 SQL 全部使用标准 JDBC + 方言 DDL，H2 端到端测试覆盖了完整链路。**上线前请务必在预发库跑一次 dry-run + execute 并抽查。**

## 9. 重跑与回滚

- 工具按主键 `DELETE` + `INSERT`，同一份源数据重复执行结果一致，可随时重跑。
- 按阶段（定义 → 实例 → 任务 → 意见）提交，中断后已完成的阶段保留，直接重跑即可补齐或覆盖。
- 旧库 `ACT_*` 始终保留，迁移失败不影响旧系统。
- 失败时仍会写出 `--report` 指定的 CSV（含已产生的阶段汇总和告警），便于定位是哪批数据出的问题。
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
src/test/java/com/joinsoft/flow/migration/
  MigrationH2Test.java      H2 端到端（dry-run/执行/重跑/草稿转换/退出码）
  OptionsTest.java          参数解析与校验
  DialectTest.java          建表脚本切分与方言推断
  ReportTest.java           阶段汇总、告警统计、CSV
  WriterTest.java           幂等写入与 NULL 绑定
  DbTest.java               URL → 驱动推断
src/test/resources/         设计器 JSON 测试夹具
```

## 11. 常见问题

**`No suitable driver found for jdbc:highgo://...`**

v1.1.1 起 jar 已内置瀚高驱动（`com.highgo:HgdbJdbc`），重新 `mvn package` 后直接用 `java -jar` 即可。仍然报错时：

1. 确认运行的是新构建的 `flow-migration.jar`（`--version` 输出版本号）；
2. 使用其他厂商驱动（达梦、Oracle 等）时，驱动不在 jar 内，需要和工具一起放进 classpath：

```bash
java -cp "flow-migration.jar;dm-jdbc.jar" com.joinsoft.flow.migration.MigrationMain --source-url ...
```

> Linux/macOS 下 classpath 分隔符是 `:`，例如 `-cp "flow-migration.jar:dm-jdbc.jar"`。

3. 或用 `--source-driver` / `--target-driver` 显式指定驱动类，缺类时工具会直接提示是哪个类找不到。

**`连接数据库失败: ... relation "act_hi_procinst" does not exist`**

URL 里的 `currentSchema` 或 `--source-schema` 指向了错误的 schema。瀚高上同一个数据库常有多个 schema（如 `joinsoft_boot` / `joinsoft_boot3`），工具按 `currentSchema` 解析表名，先确认旧数据实际在哪个 schema。

**dry-run 报「目标表缺少列」**

目标库是用旧版 `flow-schema.sql` 建的。执行最新版 `flow-schema.sql`（或先启动一次 `flow-approval` 触发 `FlowSchemaInitializer`）后重试。

**审批意见大量「跳过」**

`TYPE_='event'` 的候选人增删事件不是审批意见，会汇总成一条告警跳过；只有 `comment/back` 等真实意见才会迁移。
