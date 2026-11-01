# myflow

自研审批流引擎，以及把旧 Activiti 6 数据迁移到新引擎的工具。

## 模块

| 模块 | 说明 |
| --- | --- |
| [`flow-approval/`](flow-approval/) | 自研轻量工作流审批引擎：Spring Boot 2.7 + Vue 2 + BPMN 2.0 令牌引擎，不依赖 Activiti/Flowable 运行时 |
| [`flow-migration/`](flow-migration/) | 旧 `joinsoft-boot-module-flow`（Activiti 6.0.0）数据迁移工具，支持 MySQL、PostgreSQL、瀚高 HighGo |

两个模块都是独立的 Maven 工程，JDK 8 + Maven 3.6+。

## flow-approval

```bash
cd flow-approval

# 仅后端（开发环境默认 H2 + 内存 Token）
mvn -DskipTests package
java -jar target/flow-approval.jar

# 含前端（需要 Node 18+）
mvn -Pui -DskipTests package
java -jar target/flow-approval.jar
```

访问 <http://localhost:8080/>，默认开发账号见 [`flow-approval/README.md`](flow-approval/README.md)。

## flow-migration

```bash
cd flow-migration
mvn clean package

# 先演练，不写目标库
java -jar target/flow-migration.jar \
  --source-url "jdbc:mysql://127.0.0.1:3306/old_flow" \
  --source-user root --source-password 123456 \
  --target-url "jdbc:postgresql://127.0.0.1:5432/new_flow" \
  --target-user postgres --target-password 123456 \
  --mode dry-run

# 确认后正式执行
java -jar target/flow-migration.jar ... --mode execute
```

支持的迁移范围、参数与已知限制见 [`flow-migration/README.md`](flow-migration/README.md)。

## 目录

```text
myflow/
├─ flow-approval/      自研审批流引擎
└─ flow-migration/     Activiti 6 -> flow-approval 数据迁移工具
```
