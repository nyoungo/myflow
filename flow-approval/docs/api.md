# 接口文档

- 基础路径：`http://<host>:8080`
- 认证：除 `/auth/login`、`/auth/unauthorized`、Swagger 与静态资源外，全部需要 `Authorization: Bearer <token>`。
- 统一响应：`{ "success": true, "message": "成功", "code": 0, "result": ..., "timestamp": 1793514858513 }`；失败时 `success=false`、`code=500`、`message` 为原因。
- 分页参数：`pageNo`（默认 1）、`pageSize`（默认 10）；分页结果为 MyBatis-Plus `Page`，包含 `records`、`total`、`current`、`size`。
- Swagger UI：<http://localhost:8080/swagger-ui/index.html>

## 认证

| 方法 | 路径 | 参数 | 说明 |
| --- | --- | --- | --- |
| POST | `/auth/login` | JSON `{username,password}` | 返回 `{token, user}`，`token` 用于 Bearer |
| POST | `/auth/logout` | - | 注销当前 Token |
| GET | `/auth/userInfo` | - | 当前登录用户（`LoginUser`） |
| GET | `/auth/check` | - | Token 有效性 |
| GET | `/auth/unauthorized` | - | 未认证提示（匿名） |

## 流程操作 `/flow`

| 方法 | 路径 | 请求体 / 参数 | 说明 |
| --- | --- | --- | --- |
| POST | `/flow/start` | `StartData` | 发起流程 |
| POST | `/flow/pass` | `ApprovalData` | 审批通过并流转 |
| POST | `/flow/turnTo` | `ApprovalData` | 转办，处理人变为目标用户 |
| POST | `/flow/appoint` | `ApprovalData` | 委派，目标用户处理后回到原审批人 |
| POST | `/flow/jump` | `ApprovalData` | 跳转到 `targetTaskDefinitionKey` 指定节点 |
| POST | `/flow/backToLastNode` | `ApprovalData` | 已结束流程退回最后节点 |
| POST | `/flow/getBack` | `ApprovalData` | 下一节点未处理前取回 |
| POST | `/flow/repeal` | `ApprovalData` | 发起人撤销 |
| POST | `/flow/claim` | `ApprovalData` | 认领候选任务 |
| POST | `/flow/urge` | `ApprovalData` | 催办当前处理人 |
| POST | `/flow/terminal` | `ApprovalData` | 终止流程 |
| GET | `/flow/forecast` | `procInstId` | 预测后续路径 |

`StartData`：

```json
{
  "workflowId": "leave",
  "businessId": "LEAVE-001",
  "title": "张三的请假申请",
  "url": "/leave/LEAVE-001",
  "variables": { "days": 3, "reason": "家中有事" }
}
```

- `workflowId` 传**流程 key**（`flow_def.def_key`），引擎取该 key 的最新已发布版本。
- 生成的 `businessKey = workflowId + "." + businessId`，业务详情接口统一用它查询。

`ApprovalData`：

```json
{
  "taskId": "2116779839693860866",
  "comment": "同意",
  "targetTaskDefinitionKey": "hrApprove",
  "assignee": "u_manager",
  "variables": { "flow": 1 }
}
```

`flag` 默认 1，可在流程条件中使用；`comment` 为空时默认「审批通过」。

## 业务列表 `/flow/actBusinessData`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/flow/actBusinessData/list` | 业务主表分页 |
| GET | `/flow/actBusinessData/todoList` | 我的待办（`id` 为任务 ID） |
| GET | `/flow/actBusinessData/doneList` | 我的已办（按实例去重，`id` 为实例 ID） |
| GET | `/flow/actBusinessData/allList` | 我参与过的全部任务（含待办，按任务列出） |
| GET | `/flow/actBusinessData/myApplyAList` | 我发起的流程 |
| GET | `/flow/actBusinessData/backList` | 退回列表 |
| GET | `/flow/actBusinessData/queryById` | 按 ID 查询 |
| POST | `/flow/actBusinessData/add` | 新增业务数据 |
| PUT | `/flow/actBusinessData/edit` | 修改业务数据 |
| DELETE | `/flow/actBusinessData/delete` | 删除 |
| DELETE | `/flow/actBusinessData/deleteBatch` | 批量删除 |
| GET | `/flow/actBusinessData/exportXls` | 导出 Excel |

查询过滤参数（与 `ActBusinessData` 字段同名）：`title`、`state`、`businessId`、`defKey`、`startUserId`、`pageNo`、`pageSize`。

待办/已办/我的等列表的 `records` 元素同时包含实例字段（`businessKey`、`title`、`state`、`startUserName`、`variables`）与任务字段（`taskId`、`taskName`、`assignee`、`createTime`/`endTime`），具体以接口返回为准。

## 流程模板 `/act/model`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/act/model/list` | 模板分页（`pageNo`、`pageSize`、`name`） |
| GET | `/act/model/queryPageList` | 同上（兼容接口） |
| GET | `/act/model/queryById` | 参数 `key` 传模板 ID，返回含 `bpmnXml`、`svgXml` |
| POST | `/act/model/save` | 新建模板（自动生成默认 BPMN + DI） |
| PUT | `/act/model/edit` | 更新模板（已发布不可编辑，需转模型） |
| GET | `/act/model/deploy` | 参数 `id`，发布为新版本 |
| GET | `/act/model/export` | 参数 `id`、`type=xml`，导出 BPMN |
| DELETE | `/act/model/delete` | 参数 `id` |
| GET | `/act/model/copy` | 参数 `id`，复制模板 |
| GET | `/act/model/copyBatch` | 批量复制 |

## 流程定义 `/act/procdef`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/act/procdef/list` | 已发布定义分页（`pageNo`、`pageSize`、`name`） |
| GET | `/act/procdef/queryLastVersionList` | 每个 key 的最新版本 |
| GET | `/act/procdef/read` | 参数 `id`、`type=xml`，读取 XML |
| POST | `/act/procdef/deploy` | `multipart/form-data`，字段 `file`，部署 BPMN 文件 |
| GET | `/act/procdef/convertToModel` | 参数 `id`，复制为可编辑草稿 |
| POST | `/act/procdef/status` | JSON `{id,state}`，`1` 激活 / `2` 挂起 |
| POST | `/act/procdef/delete` | JSON 数组 `[deploymentId]` |

## 运行中实例 `/act/execution`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/act/execution/running` | 运行中实例分页 |
| POST | `/act/execution/repeal` | 撤销（`ApprovalData.taskId` 对应的实例） |
| POST | `/act/execution/terminal` | 终止 |

## 模板/实例查询 `/flow/model`

| 方法 | 路径 | 参数 | 说明 |
| --- | --- | --- | --- |
| GET | `/flow/model/groupList` | - | 按分类分组返回可发起流程（含最新已发布版本与部署时间） |
| GET | `/flow/model/getDefinitionByDefId` | `procDefId` | 用户任务节点列表 |
| GET | `/flow/model/getUsersByDefId` | `procDefId`、`taskDefId` | 节点可选审批人 |
| GET | `/flow/model/getPic` | `businessKey` | 流程图；**Base64 SVG 位于 `message`**，`result` 为空 |
| GET | `/flow/model/getHis` | `businessKey` | 审批历史（任务 + 审批意见） |
| GET | `/flow/model/getFlowInfo` | `businessKey` | 实例、XML、历史、活动轨迹 |

> `getPic` 的返回位置沿用参考项目：`Result.message` 存放 Base64（不含 `data:image/svg+xml;base64,` 前缀）。

## 用户与扩展

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/sys/user/list` | 用户分页（`pageNo`、`pageSize`、`username`、`realname`） |
| GET | `/sys/user/current` | 当前用户 |
| GET | `/service/viewXmlFromModelId` | 参数 `modelId` 查看模型 XML |
| GET | `/service/model/{modelId}/nodes` | 模型节点列表 |
| POST | `/service/model/{modelId}/saveBusi` | 保存业务数据并启动流程 |

## 错误与状态码

| 场景 | HTTP | `success` | 示例 `message` |
| --- | --- | --- | --- |
| 未登录 / Token 失效 | 401 | false | `未登录或登录已过期` |
| 无权限 | 401 | false | `无权限` |
| 业务校验失败 | 200 | false | `该流程已挂起!`、`任务不存在` |
| 参数错误 | 200 | false | `BPMN 内容为空` |

## 实例状态

| `flow_inst.state` | 含义 |
| --- | --- |
| 1 | 运行中 |
| 2 | 已完成 |
| 3 | 已终止 |
| 4 | 已撤销 |

任务状态：`ACTIVE`（待办）、`COMPLETED`（已办）、`CANCELED`（取消）。

审批记录类型：`comment`、`back`、`jump`、`cancel`、`terminal`、`system`。
