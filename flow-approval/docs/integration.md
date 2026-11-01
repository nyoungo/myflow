# 接入指南

本文说明业务系统如何与 `flow-approval` 集成：既可以走 HTTP 接口，也可以在同一个 Spring 容器内直接调用 `FlowUtil`（与参考项目保持一致）。

## 1. 认证

先登录获取 Token，后续请求携带 `Authorization: Bearer <token>`：

```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"zhangsan","password":"123456"}'
```

```json
{"success":true,"result":{"token":"eyJ...","user":{"id":"u_zhangsan","username":"zhangsan",...}}}
```

Token 无状态（JWT），服务端只保存登录态用于注销/续期：

- 开发/测试：`flow.token-store=memory`。
- 生产：`flow.token-store=redis`，多实例共享，Key 前缀 `flow:token:`。

## 2. 发起流程

### 2.1 HTTP

```bash
curl -X POST http://localhost:8080/flow/start \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "workflowId": "leave",
    "businessId": "LEAVE-20261101-001",
    "title": "张三的请假申请",
    "url": "/leave/LEAVE-20261101-001",
    "variables": {"days": 3, "reason": "家中有事"}
  }'
```

### 2.2 同进程（推荐给老业务系统迁移）

```java
StartData data = new StartData();
data.setWorkflowId("leave");                 // 流程 key
data.setBusinessId("LEAVE-20261101-001");    // 业务主键
data.setTitle("张三的请假申请");
data.setUrl("/leave/LEAVE-20261101-001");
data.setVariables(FlowUtil.objectToMap(leaveOrder));

Result<?> result = FlowUtil.startFlow(data);
if (!Boolean.TRUE.equals(result.isSuccess())) {
    throw new JoinsoftException(result.getMessage());
}
```

- `workflowId` 为 `flow_def.def_key`，引擎自动选择该 key 的最新已发布版本。
- 生成的流程实例 `business_key = workflowId + "." + businessId`。
- 业务表不需要新增流程字段；通过 `business_id` 与 `flow_inst` 关联。

## 3. 业务列表带出流程信息

参考项目约定：业务实体父类包含 `businessData` 字段，业务主键字段名为 `id`。分页查询后调用：

```java
IPage<LeaveOrder> page = leaveOrderService.page(new Page<>(pageNo, pageSize));
FlowUtil.handlePageList(page);
```

`handlePageList` 会：

1. 反射读取每条记录的 `getId()`；
2. 按 `business_id` 批量查询 `flow_inst`；
3. 反射写入父类 `businessData` 字段（`ActBusinessData`）。

前端即可直接读取 `record.businessData.state`、`record.businessData.taskName` 等字段，无需再查流程表。

> 如果业务实体没有父类 `businessData` 字段，可改为直接调用
> `actBusinessDataService.getByBusinessIds(ids)` 自行组装。

## 4. 审批操作

```java
ApprovalData approval = new ApprovalData();
approval.setTaskId(taskId);
approval.setComment("同意");
approval.setVariables(Collections.singletonMap("flow", 1L));
flowService.pass(approval);
```

- `flag` 默认 1，可在 BPMN 条件表达式中使用。
- `comment` 为空时写入「审批通过」。
- 转办 `turnTo`、委派 `appoint`、跳转 `jump` 需要 `assignee` / `targetTaskDefinitionKey`。
- 撤销 `repeal` 默认保留审批记录（`state=4`）；`flow.repeal-keep-record=false` 时删除流程数据。

## 5. 业务回调 Hook

实现 `com.joinsoft.modules.flow.hook.ActivitiHook`，并以**流程 key 作为 Bean 名称**注册：

```java
@Component("leave")
public class LeaveHook implements ActivitiHook {

    @Override
    public void onStart(String businessId, String processInstanceId) {
        // 流程发起：回写业务状态为“审批中”
    }

    @Override
    public void onComplete(String businessId, String processInstanceId, String taskName, Long flow) {
        // 每个节点审批完成
    }

    @Override
    public void onFinish(String businessId, String processInstanceId, Long flow) {
        // 流程结束：回写业务状态为“已通过”
    }

    @Override
    public void onRepeal(String businessId, String processInstanceId) {
        // 撤销
    }
}
```

支持的钩子：`onStart`、`onComplete`、`onFinish`、`onJump`、`onBack`、`onRepeal`、`onTerminal`。Hook 异常会记录日志，不会阻断流程流转。

## 6. 消息通知

默认 `LoggingFlowNotifier` 只写日志。实现自己的 `FlowNotifier` Bean 即可替换（邮件、短信、WebSocket、站内信）：

```java
@Component
public class MailFlowNotifier implements FlowNotifier {

    @Override
    public void notify(FlowMessage message) {
        // message.getType()：TODO / COMPLETE / FINISH / URGE ...
        // message.getReceiverIds()、getTitle()、getContent()、getBusinessKey()
    }
}
```

触发点：任务到达（`noticeApprovalUser`）、流程结束通知发起人（`noticeStartUser`）、催办。

## 7. 审批人解析规则

优先级从高到低：

1. 审批接口显式传入的 `assignee`；
2. 节点 `assignee`（支持 `${变量}`，用户名或用户 ID）；
3. 节点 `candidateUsers`；
4. 节点 `candidateGroups`（按 `sys_user.role_codes` 查用户）。

补充规则：

- 空审批人 → 自动通过并写审批记录。
- `userFilter` 支持 `SameDepAsStartUser`、`SameDepAsLastUser`、`Leader`。
- `autoPass=true` 且审批人与上一节点处理人相同时自动通过。
- 用户名会归一化为用户 ID 后写入 `flow_task.assignee`，便于待办 SQL 查询。

## 8. 配置项

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `flow.token-store` | `memory` | `memory` / `redis` |
| `flow.repeal-keep-record` | `true` | 撤销时是否保留审计数据 |
| `flow.demo.enabled` | dev `true`，prod `false` | 演示用户/流程/实例 |
| `flow.bootstrap-admin.enabled` | `true` | 无用户时创建管理员 |
| `flow.bootstrap-admin.username` | `admin` | 初始管理员用户名 |
| `flow.bootstrap-admin.password` | 空 | 为空时生成随机密码并打印日志 |
| `flow.jwt.secret` | 内置示例 | 生产必须替换 |
| `flow.jwt.expire-minutes` | `720` | Token 有效期 |
| `flow.security.password-salt` | `flow-approval` | 密码 MD5 盐 |

## 9. 前端接入

管理端使用 Hash 路由，静态资源由后端托管：

- 开发：`cd ui && npm run dev`（3000），`webpack.config.js` 已把 `/auth`、`/flow`、`/act`、`/service`、`/sys` 代理到 8080。
- 生产：`mvn -Pui package` 会把 `ui/dist` 复制到 `target/classes/static`，直接访问 `http://host:8080/`。
- 登录态保存于 `localStorage`：`flow_approval_token`、`flow_approval_user`；401 时自动跳转登录页。

## 10. 与参考项目的差异

| 项目 | 参考项目 | 本项目 |
| --- | --- | --- |
| 引擎 | Activiti | 自研令牌引擎（顺序/排他/并行） |
| 表 | ACT_* 20+ 张 | 5 张 |
| 模型与定义 | ACT_RE_MODEL + ACT_RE_PROCDEF | `flow_def` 用 `version=0` 区分草稿 |
| 历史 | ACT_HI_* | `flow_task` + `flow_comment` + `flow_inst.activities` |
| 用户/角色 | 独立表 | `sys_user.role_codes` CSV |
| 接口 | 原路径 | 保持路径、方法、参数名与主要字段兼容 |

接口路径、请求参数名、`StartData` / `ApprovalData` / `Result` 字段与 `FlowUtil` 方法签名均保持兼容；依赖 Activiti 特有 API（如 `ProcessEngine`、`RepositoryService`）的代码需要改造为调用本项目的 `FlowService` / `IFlowService`。
