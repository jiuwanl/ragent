# 后端镜像：多阶段构建（构建期 maven:3.9 + JDK17，运行期 jre17）
# 构建上下文为仓库根：docker compose -f resources/docker/local/local-dev.compose.yaml -f resources/docker/app/app-compose.yaml up -d --build
FROM maven:3.9-eclipse-temurin-17 AS build

# 容器内 maven 堆上限，避免 16GB 宿主机上构建期与中间件抢内存
ENV MAVEN_OPTS=-Xmx1024m
WORKDIR /workspace

# ① 只拷 POM 建依赖缓存层：源码变更不触发依赖重下
#    8 个 pom 全拷：root 的 <modules> 声明了全部 7 模块，reactor 解析缺一不可
COPY pom.xml ./
COPY framework/pom.xml framework/
COPY infra-ai/pom.xml infra-ai/
COPY system/pom.xml system/
COPY rag/pom.xml rag/
COPY agent/pom.xml agent/
COPY bootstrap/pom.xml bootstrap/
COPY mcp-server/pom.xml mcp-server/
RUN mvn -B -q -pl bootstrap -am dependency:go-offline -DskipTests || true

# ② 源码进来只构建 bootstrap 及其上游模块；跳过测试/格式化/覆盖率，产物一个 fat jar
COPY framework/src framework/src
COPY infra-ai/src infra-ai/src
COPY system/src system/src
COPY rag/src rag/src
COPY agent/src agent/src
COPY bootstrap/src bootstrap/src
RUN mvn -B -pl bootstrap -am package -DskipTests \
    -Dspotless.apply.skip=true -Djacoco.skip=true

# ③ 运行期：jre + curl（compose healthcheck 用）
FROM eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/bootstrap/target/*.jar app.jar
# -Xms 给底仓、MaxRAMPercentage 控上限，配合 compose mem_limit 1536m
ENV JAVA_TOOL_OPTIONS="-Xms256m -XX:MaxRAMPercentage=65"
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "app.jar"]
