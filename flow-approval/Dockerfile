# syntax=docker/dockerfile:1

# ---------- 1. 前端构建（Vue2 + Ant Design Vue + Webpack5） ----------
FROM node:20-alpine AS ui-build
WORKDIR /workspace/ui
COPY ui/package.json ui/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY ui/ ./
RUN npm run build

# ---------- 2. 后端构建（JDK8 + Maven） ----------
FROM maven:3.9.9-eclipse-temurin-8 AS backend-build
WORKDIR /workspace
COPY pom.xml ./
# 预热依赖，失败不阻塞（部分插件在离线阶段解析不到时仍可在正式构建阶段下载）
RUN mvn -B -q -DskipTests dependency:go-offline || true
COPY src ./src
# 将前端产物并入 Spring Boot 静态资源，随后打包
COPY --from=ui-build /workspace/ui/dist ./src/main/resources/static
RUN mvn -B -DskipTests clean package

# ---------- 3. 运行镜像（JRE8） ----------
FROM eclipse-temurin:8-jre-jammy

LABEL org.opencontainers.image.title="flow-approval" \
      org.opencontainers.image.description="Lightweight workflow approval platform"

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system flow \
    && useradd --system --gid flow --home-dir /app --shell /usr/sbin/nologin flow

WORKDIR /app
COPY --from=backend-build /workspace/target/flow-approval.jar /app/app.jar
RUN mkdir -p /app/logs && chown -R flow:flow /app

USER flow

EXPOSE 8080
ENV TZ=Asia/Shanghai \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-Xms256m -Xmx512m -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8080/ > /dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
