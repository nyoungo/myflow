# flow-approval

自研轻量工作流审批工具，参考 `joinsoft-boot-module-flow` 的接口、传参与 `FlowUtil` 用法，但**不使用 Activiti / Flowable**：BPMN 2.0 XML 仅作为流程定义格式，解析与流转由项目内的令牌引擎完成。

- 后端：Spring Boot 2.7.18 + MyBatis-Plus 3.5.1 + Shiro 1.8.0 + JWT(auth0 3.11.0) + Swagger(springfox 3.0.0) + Druid + Redis + POI + Quartz + Lombok，JDK 8。
- 前端：Vue 2.7 + Ant Design Vue 1.7 + bpmn-js 11 + Webpack 5，构建产物由后端 `classpath:/static` 托管。
- 数据库：5 张表（`flow_def`、`flow_inst`、`flow_task`、`flow_comment`、`sys_user`），开发环境 H2，生产环境 MySQL 8。
- 包名与兼容类：`com.joinsoft.modules.flow.*`、`com.joinsoft.common.api.vo.Result`、`FlowUtil`、`ActBusinessData`、`ActReModelEntity`、`ActReProcdefEntity`、`ActRuExecution`、`ActHiTaskinst`。

## 功能

| 模块 | 能力 |
| --- | --- |
| 流程模型 | 模板增删改查、复制、导出 XML、bpmn-js 设计器（拖拽 + 属性面板 + XML 源码）、发布为流程定义 |
| 流程定义 | 已发布定义查询、XML 查看、挂起/激活、转模型、删除、BPMN 文件部署 |
| 流程实例 | 发起、审批通过、转办、委派、跳转、退回、撤销、终止、认领、催办、完成预测、流程图、审批历史 |
| 任务中心 | 待办、已办、我的申请、全部（我参与过的）、退回列表、标题搜索、分页 |
| 引擎能力 | 顺序流、排他网关（条件表达式）、并行网关 fork/join、多令牌并发、`${变量}` 审批人、自动通过、通知 SPI、Hook |
| 认证 | Shiro 无状态 JWT；TokenStore 可切内存/Redis |

## 目录结构

```
flow-approval
├─ pom.xml                     # 后端构建；-Pui 时联动 npm 构建前端
├─ src/main/java/com/joinsoft
│  ├─ auth/                    # Shiro + JWT + TokenStore
│  ├─ common/                  # Result / LoginUser / 工具类
│  ├─ config/                  # Swagger、MyBatisPlus、演示数据、初始管理员
│  └─ modules
│     ├─ flow
│     │  ├─ controller/        # 与原项目一致的接口路径
│     │  ├─ engine/            # BPMN 解析器、表达式、令牌引擎、SVG、审批人解析
│     │  ├─ entity/ mapper/ service/ dto/ hook/ notify/ util/
│     └─ system/               # 简化用户表
├─ src/main/resources
│  ├─ application.yml          # 通用配置（dev 为默认 profile）
│  ├─ application-dev.yml      # H2 文件库 + 内存 Token
│  ├─ application-test.yml     # H2 内存库（测试）
│  ├─ application-prod.yml     # MySQL 8 + Redis
│  ├─ bpmn/                    # 演示流程
│  └─ db/{h2,mysql}/schema.sql
├─ src/test/java               # 解析器 / 表达式 / 端到端接口测试
├─ ui/                         # Vue2 + Ant Design Vue 管理端
├─ Dockerfile                  # 多阶段构建（Node + Maven + JRE8）
├─ docker-compose.yml          # MySQL 8 + Redis + 应用
└─ docs/                       # 接口与接入文档
```

## 快速开始（开发环境：H2 + 内存 Token）

要求：JDK 8、Maven 3.6+；前端构建需要 Node 18+（本项目验证于 Node 20 / npm 10）。

```bash
# 1. 构建（含前端）
mvn -Pui -DskipTests package

# 2. 启动（默认 profile=dev，H2 文件库位于 ./data）
java -jar target/flow-approval.jar

# 或仅后端 + 前端热更新
mvn -DskipTests package
java -jar target/flow-approval.jar
cd ui && npm install && npm run dev     # http://localhost:3000，代理到 8080
```

访问 <http://localhost:8080/>（后端内置前端时）或 <http://localhost:3000/>（开发服务器）。

演示账号（`flow.demo.enabled=true` 时自动创建）：

| 用户名 | 密码 | 角色 |
| --- | --- | --- |
| admin | admin123 | 管理员（人事审批节点） |
| manager | manager123 | 部门经理 |
| zhangsan | 123456 | 员工 |
| lisi | 123456 | 员工 |
| finadmin | 123456 | 财务/经理 |

演示流程：`leave`（部门经理 → 人事）、`expense`（金额 > 1000：总经理 → 财务，否则直接财务）。

重置开发数据：停止应用后删除 `flow-approval/data` 目录。

## 生产部署（MySQL 8 + Redis）

1. 初始化数据库（库名与 `MYSQL_DB` 一致）：

```bash
mysql -uroot -p -e "CREATE DATABASE flow_approval DEFAULT CHARSET utf8mb4;"
mysql -uroot -p flow_approval < src/main/resources/db/mysql/schema.sql
```

2. 启动（`prod` profile 默认关闭演示数据，首次启动自动创建管理员）：

```bash
java -jar target/flow-approval.jar \
  --spring.profiles.active=prod \
  --MYSQL_HOST=127.0.0.1 --MYSQL_DB=flow_approval \
  --MYSQL_USER=flow --MYSQL_PASSWORD=change-me \
  --REDIS_HOST=127.0.0.1 \
  --FLOW_JWT_SECRET=please-change-this-secret-in-production \
  --FLOW_ADMIN_PASSWORD=change-me-now
```

也可以使用环境变量：`MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DB`、`MYSQL_USER`、`MYSQL_PASSWORD`、`REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`、`REDIS_DB`、`FLOW_JWT_SECRET`、`FLOW_ADMIN_USERNAME`、`FLOW_ADMIN_PASSWORD`、`FLOW_PASSWORD_SALT`。

> 未设置 `FLOW_ADMIN_PASSWORD` 时会生成随机临时密码并打印在启动日志中（`docker compose logs app` 可查看）；登录后请立即修改。`FLOW_JWT_SECRET` 务必替换为随机长字符串。
> 生产环境 `flow.token-store=redis`，多实例共享登录态；开发/测试使用内存实现。

### Docker Compose 一键启动

```bash
cp .env.example .env            # 修改其中的管理员密码、JWT 密钥、数据库/Redis 密码
docker compose up -d --build

# 应用：http://localhost:8080    MySQL：3306    Redis：6379
docker compose logs -f app       # 查看启动日志（含初始管理员创建信息）
```

`docker-compose.yml` 会在 MySQL 首次启动时执行 `src/main/resources/db/mysql/schema.sql`，并为应用设置 `prod` profile、管理员账号与 JWT 密钥。镜像为三阶段构建：Node 20 构建前端 → Maven/JDK 8 打包（前端产物并入 `classpath:/static`）→ JRE 8 运行；应用以非 root 用户运行。

常用环境变量：`APP_PORT`、`MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`、`REDIS_PASSWORD`、`FLOW_ADMIN_USERNAME`、`FLOW_ADMIN_PASSWORD`、`FLOW_JWT_SECRET`、`JAVA_OPTS`。数据与日志分别保存在命名卷 `mysql-data`、`redis-data`、`app-logs` 中；彻底重置使用 `docker compose down -v`。

> Docker 构建需要能访问 Docker Hub、npm 与 Maven 仓库。本项目开发机未安装 Docker，Dockerfile/compose 已做静态校验，但未在本机实机启动验证。

## BPMN 扩展属性

支持两种写法（解析器按属性 `localName` 读取，`flow:` 前缀优先）：

```xml
<userTask id="deptApprove" name="部门经理审批" assignee="manager">
  <extensionElements>
    <flow:autoPass xmlns:flow="http://joinsoft.com/flow">true</flow:autoPass>
    <flow:userFilter xmlns:flow="http://joinsoft.com/flow">leader</flow:userFilter>
    <flow:noticeStartUser xmlns:flow="http://joinsoft.com/flow">true</flow:noticeStartUser>
    <flow:noticeApprovalUser xmlns:flow="http://joinsoft.com/flow">true</flow:noticeApprovalUser>
  </extensionElements>
</userTask>
<sequenceFlow id="flow2" sourceRef="gateway" targetRef="financeApprove">
  <conditionExpression xsi:type="tFormalExpression"><![CDATA[${amount > 1000}]]></conditionExpression>
</sequenceFlow>
```

| 属性 | 作用 |
| --- | --- |
| `assignee` | 审批人，支持用户名或用户 ID，多个用逗号分隔，支持 `${startUserId}` 等变量 |
| `candidateUsers` | 候选用户（逗号分隔），待办对所有候选人可见 |
| `candidateGroups` | 候选角色（逗号分隔），按 `sys_user.role_codes` 匹配 |
| `formKey` | 表单标识，原样写入任务 |
| `userFilter` | 审批人过滤：`Leader`（领导/经理）、`SameDepAsStartUser`、`SameDepAsLastUser` |
| `autoPass` | 审批人与上一节点相同则自动通过 |
| `noticeStartUser` / `noticeApprovalUser` | 到达节点/通过时是否通知发起人/审批人 |

设计器（bpmn-js）通过 `ui/src/bpmn/flow-moddle.js` 注册 `http://joinsoft.com/flow` 扩展，属性面板可编辑名称、审批人、候选用户/角色、表单 Key、条件表达式与说明。

## 接口与认证

所有业务接口需携带 JWT：

```
Authorization: Bearer <token>
```

登录：`POST /auth/login`，请求体 `{"username":"admin","password":"admin123"}`。

统一返回结构：

```json
{ "success": true, "message": "成功", "code": 0, "result": {}, "timestamp": 1793514858513 }
```

接口清单与参数见 [docs/api.md](docs/api.md)；业务系统接入方式见 [docs/integration.md](docs/integration.md)。

## 数据库表

| 表 | 说明 |
| --- | --- |
| `flow_def` | 流程模板与已发布定义（`version=0` 草稿，`version>=1` 已发布；`status` DRAFT/PUBLISHED/SUSPENDED） |
| `flow_inst` | 流程实例 + 业务主表（`business_key` 唯一关联业务，`active_tokens` 保存活动令牌 JSON，`activities` 保存流转轨迹） |
| `flow_task` | 运行中任务与历史任务（`status` ACTIVE/COMPLETED/CANCELED，含候选人、委派、耗时） |
| `flow_comment` | 审批记录（意见、退回/跳转/终止等类型） |
| `sys_user` | 简化用户表（`role_codes` 形如 `,admin,manager,`，`leader_id` 支持 Leader 审批） |

## 测试

```bash
mvn test          # 11 个用例：BPMN 解析、表达式、端到端发起→审批→完成
mvn -Pui package  # 前端构建 + 打包（产物含 classpath:/static）
```

端到端测试使用 H2 内存库与内存 TokenStore，覆盖登录、发起、部门经理审批、人事审批、流程完成、匿名 401、未知流程报错。

## 已知限制

- 仅实现常用的顺序 / 排他 / 并行网关，不含子流程、边界事件、定时器、多实例等完整 BPMN 语义。
- 用户体系为演示级：角色用 `role_codes` CSV，部门/上级用 `dept_id`/`leader_id`，未建角色、部门、权限表。
- 前端未做按钮级权限控制；生产接入时建议按 `role_codes` 隐藏「流程模板 / 流程定义」菜单。
- 附件、表单渲染、消息中心未实现；通知默认写日志，可通过 `FlowNotifier` 扩展。
- 开发环境 H2 与生产 MySQL 在 SQL 方言上已做兼容，但未在本机验证 MySQL/Redis/Docker 实机联调。
