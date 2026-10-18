# Ragent 本地使用手册

本手册面向本机（16GB Windows）从零跑通 Ragent，所有命令与坑点均来自实际验证记录。

## 1. 这是什么

Ragent 是一个本地部署的 Agentic RAG 知识库平台：文档入库（解析 → 分块 → 向量化）→ 混合检索（向量/关键词/图谱/联网）→ 大模型生成带引用的回答，并带 Agent ReAct 执行引擎与完整管理后台。前后端分离，后端 Spring Boot（端口 9090，上下文 `/api/ragent`），前端 React + Vite（端口 5173，开发代理转发 API）。

**双执行引擎**（`ragent.engine.type` 配置）：

- **v1 workflow**：固定编排管线——问题重写 → 意图识别 → 多路召回 → 组装 → 生成
- **v2 agent**：模型自主决策工具调用（知识检索 / MCP / Skills）的 ReAct 循环（当前主力）

**数据流一行图**：

```
文档 → 解析(Tika) → 分块 → Embedding(百炼) → PG 向量库 + 关键词索引
提问 → 意图/路由 → 并行召回 → RRF 融合 → Rerank(百炼) → 组装 → LLM(mimo) → 流式回答 + 引用
```

## 2. 环境与前置

- **内存约束**：16GB 无独显。**严禁本地模型**（Ollama 等 CPU 推理会卡死系统），所有模型调用走云端 API
- **Docker Desktop**：安装在 `D:\DockerDesktop`，**开机不会自启**，需手动拉起，等 ~15 秒后 `docker info` 出版本号即就绪
- **镜像**：PG/Redis/RocketMQ/rustfs 镜像已拉取到本地，无需重新下载
- **API Key**：存放在 `~/.ragent-keys.env`（`export BAILIAN_API_KEY=...` / `export MIMO_API_KEY=...`），任何 curl 或启动后端前先 `source ~/.ragent-keys.env`；**不要把 key 写进命令行明文或提交到 git**

## 3. 中间件栈

定义文件：`resources/docker/local/local-dev.compose.yaml`（项目名 `ragent-local`，每个容器带内存上限，适配 16GB）。

| 容器 | 镜像 | 端口 | 用途 | 内存上限 |
|:---|:---|:---|:---|:---|
| ragent-postgres | pgvector/pgvector:pg16 | 5432 | 业务库 + 向量库（postgres/postgres，库名 ragent） | 512M |
| ragent-redis | redis:7-alpine | 6379 | 缓存 / 分布式锁 / 公平排队（密码 123456） | 128M |
| ragent-rmqnamesrv | apache/rocketmq:5.2.0 | 9876 | RocketMQ NameServer | 384M |
| ragent-rmqbroker | apache/rocketmq:5.2.0 | 10911/10909 | RocketMQ Broker（分块任务可靠投递） | 768M |
| ragent-rustfs | rustfs/rustfs | 9000/9001 | S3 对象存储（rustfsadmin/rustfsadmin） | 256M |
| ragent-elasticsearch | elasticsearch:8.17.0 | 9200 | 关键词检索通道（共享索引 rag_keyword_store，首次启动自动装 IK 插件） | 1024M |

- **首次启动**（数据卷为空时）自动按字母序执行 `resources/database/schema_pg.sql` + `init_data_pg.sql`：建 32 张表 + 种子数据（admin 账号）
- **数据卷保留**：`down` 保留 pg-data / redis-data / rustfs-data / es-data；`down -v` 才清空
- **ES 首次启动较慢**：要下载镜像（~650MB）并安装 IK 插件（重启后插件已在镜像层，秒起）
- Milvus / Neo4j / Langfuse 在配置里默认关闭，**无需启动**

## 4. 模型与 API Key 分工

配置：`bootstrap/src/main/resources/application.yaml`。

| 用途 | 供应商 | 模型 | 说明 |
|:---|:---|:---|:---|
| 主对话（v2 agent，`agent.chat`） | mimo | `mimo-v2.6-flash` | 流式 + tools 已验证，省百炼额度 |
| Embedding | 百炼（DashScope 兼容模式） | `text-embedding-v4`（1536 维） | 知识库入库向量化 |
| Rerank | 百炼 | `qwen3-rerank` | 检索精排 |
| 模型档位（v1 等） | 百炼 | fast=qwen-flash / standard=qwen3-max | 启动时校验档位配置 |

- Key 通过环境变量注入：`${MIMO_API_KEY:}`、`${BAILIAN_API_KEY:}`——先 `source ~/.ragent-keys.env` 再启动后端
- 百炼按模型限额度（约 1M/模型），所以主对话流量走 mimo，百炼只承担 embedding/rerank/辅助档位

## 5. 启停命令

### 启动（顺序：Docker → 后端 → 前端）

```bash
# 1. Docker Desktop（不自启，先拉起；等 ~15s，docker info 有版本号即就绪）
"/d/DockerDesktop/Docker Desktop.exe" &

# 2. 中间件栈（等 postgres healthy 再起后端）
docker compose -f resources/docker/local/local-dev.compose.yaml up -d

# 3. 后端（必须先 source key）
source ~/.ragent-keys.env && cd bootstrap && mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx2g"
# 约 10-30s 后日志出现 "Started RagentApplication"，端口 9090

# 4. 前端（另开终端）
cd frontend && npm run dev
# 访问 http://localhost:5173
```

### 修改代码后的关键步骤

`bootstrap` 目录下 `mvn spring-boot:run` 解析兄弟模块走 **本地仓库已安装 jar**，不是工作区源码。改了 `system` / `rag` / `agent` 等模块后必须先安装，否则运行的是旧代码（典型症状：新接口 404 `No static resource ...`，或改了逻辑行为却是旧的）：

```bash
mvn install -pl <模块名> -am -DskipTests   # 例：mvn install -pl system -am -DskipTests
# 然后重启后端
```

> **本机双仓库坑**：PATH 里的 `mvn`（`D:\DevTools\apache-maven-3.8.9`）全局 settings 指向 `D:\DevTools\.m2`，仓库根目录的 `./mvnw` 指向 `C:\Users\Lenovo\.m2`——**install 和起后端必须用同一个**，否则一边装的新 jar 另一边永远读不到（详见 §8）。

### 停止

```bash
# 1. 停 TaskStop/关终端只杀了 mvn 外壳，JVM 子进程会残留占着 9090，必须手动清：
netstat -ano | grep -E ":(9090|5173)\s.*LISTENING"   # 找 PID
taskkill //PID <pid> //F

# 2. 中间件（保留数据卷）
docker compose -f resources/docker/local/local-dev.compose.yaml down
```

### 容器化一键部署（应用层镜像，可选）

不想在宿主机装 JDK/Node 时，后端与前端也能进容器——中间件定义仍只有一份（叠加文件，防双份漂移）：

```bash
# 构建镜像 + 起全部 8 容器（首次 5~8 分钟，之后秒级）
docker compose -f resources/docker/local/local-dev.compose.yaml -f resources/docker/app/app-compose.yaml up -d --build

# 停止（数据卷保留，重起数据不丢；加 -v 才清空）
docker compose -f resources/docker/local/local-dev.compose.yaml -f resources/docker/app/app-compose.yaml down
```

- 入口：前端 `http://localhost:8080`（nginx 托管 SPA 并反代 `/api`，SSE 已关缓冲）；后端仍可直连 `:9090`
- 前置：`~/.ragent-keys.env` 存放 API Key（`MIMO_API_KEY=...` 换行 `BAILIAN_API_KEY=...`），该文件经 env_file 注入容器，不进镜像与仓库
- 叠加层已把 RocketMQ broker 的 `brokerIP1` 覆盖为服务名 `rmqbroker`（local-dev 里的 `127.0.0.1` 是给宿主机 JVM 用的，容器内沿用会导致事务消息连到自己）
- 内存预算：中间件 2048M + ES 1024M + backend 1536M + nginx 64M ≈ 4.7G；maven/node 构建瞬时再加 1~1.5G
- MCP demo 服务（:9099）不在该栈内，后端启动日志出现「连接 MCP Server [default] 失败，跳过工具注册」属预期，不影响问答主链路

### 测试（分层执行）

测试用 JUnit `@Tag` 分三层，默认层在**不开任何中间件**的干净环境即可全绿：

```bash
# ① 默认层（CI 同款）：排除 live / integration，无需中间件与云 Key
mvn test

# ② 本地全套：Docker 栈全起 + ~/.ragent-keys.env 已加载（含真实环境与真实 API 的 live 测试）
mvn test -DexcludedGroups=

# ③ 集成层：只需 ragent-redis 在跑（公平限流器排队语义 + Lua 原子 claim 的真实验证）
mvn test -Dgroups=integration -DexcludedGroups=live   # 两个 -D 必须同时给

# ④ 覆盖率（只出数不设红线）
mvn verify   # 打开 rag/target/site/jacoco/index.html
```

- `live`（13 个）：10 个直连中间件的 `@SpringBootTest` + 3 个带 env 守卫的真实 API 测试（MinerU 解析 / You.com 联网通道 / youcom_search MCP 工具）
- `integration`（1 个）：`FairDistributedRateLimiterRealRedisTest`，地址读 `TEST_REDIS_URL`（缺省 `redis://:123456@127.0.0.1:6379` 对齐 §3 compose 密码）

## 6. 账号

- **种子管理员**：`admin` / `admin`（明文入库，首次登录成功后自动升级为 BCrypt 哈希，之后一直是哈希比对）
- **自助注册**：登录页 →「注册账号」，或访问 `http://localhost:5173/register`；接口 `POST /api/ragent/auth/register`（匿名），注册即登录，角色固定 `user`
  - 用户名 2–64 字符、密码 6–64 字符；`admin` 为保留用户名
  - 密码一律 BCrypt 存储（`$2a$` 开头 60 字符）
- 管理员可在管理后台「用户管理」建号；用户可自助修改密码

**curl 调用注意**：登录后请求头为 `Authorization: <token>`，**不带 `Bearer` 前缀**（Sa-Token `token-name: Authorization`）。

```bash
BASE=http://localhost:9090/api/ragent
TOKEN=$(curl -s -X POST $BASE/auth/login -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
curl -s $BASE/user/me -H "Authorization: $TOKEN"
```

## 7. 功能 Walkthrough（知识库问答全流程）

> 以下三个必填字段是 API 直调时最容易踩的（前端表单会带上，脚本调用容易漏）。

1. **建知识库** `POST /knowledge-base`
   - **`collectionName` 必填**（服务端不自动生成）：小写字母数字 `^[a-z0-9]+$`，≤50 字符
   ```json
   {"name": "my-kb", "collectionName": "mykb", "description": "demo"}
   ```
2. **上传文档** `POST /knowledge-base/{kbId}/docs/upload`（multipart）
   - **`sourceType=file` 必填**（file/url）；**`processMode=chunk` 必填**（chunk=直接分块；pipeline 还需 pipelineId）
   ```bash
   curl -s -X POST "$BASE/knowledge-base/$KB_ID/docs/upload" \
     -H "Authorization: $TOKEN" \
     -F "file=@C:/path/to/doc.md" -F "sourceType=file" -F "processMode=chunk"
   ```
   - 注意：`@` 后用 **Windows 路径**（`C:/...`），Git Bash 的 `/c/...` 读不到
3. **触发入库** `POST /knowledge-base/docs/{docId}/chunk`
   - 走 RocketMQ 异步分块：解析 → 分块 → 百炼 Embedding → 写入 PG 向量表
   - 日志确认：`bailian embedding 完成 - 模型：text-embedding-v4` → `批量写入向量到 PostgreSQL`
4. **问答**：前端 `http://localhost:5173/chat` 提问，命中检索时回答带引用来源
   - 后处理链：去重 → RRF 融合 → Rerank → 证据门控；日志可见 `精排分布: ...最高 0.96`
5. **管理后台** `http://localhost:5173/admin`（需 admin 角色），常用页面：

| 分组 | 页面 |
|:---|:---|
| 概览 | 仪表盘（v2 Agent 运行概览、指标趋势） |
| 知识 | 知识库列表 → 文档管理 → Chunk 明细；知识图谱 |
| 检索 | 意图树 / 意图列表、查询词映射、示例问题 |
| 入库 | 入库流水线（可编排 Pipeline）、远程刷新 |
| Agent | 智能体人设 / 提示词、技能（Skills）管理 |
| 运行 | RAG Trace 链路详情、业务变更日志（配置 Diff） |
| 系统 | 用户管理、系统设置（模型 / 档位 / 检索通道开关） |

## 8. 已知坑清单

| 坑 | 现象 | 对策 |
|:---|:---|:---|
| **Git Bash 中文转码** | curl `-d` 内联中文变 GBK，后端报 `Invalid UTF-8` / `Character decoding failed` | JSON body 一律用 ASCII（英文名/ID）；URL 参数中文用 `question@C:/path/to/file.txt` 文件载荷；multipart 上传不受影响 |
| **修改代码不生效** | 新接口 404 `No static resource`，或改了逻辑行为仍是旧的 | 见 §5：先 `mvn install -pl <模块> -am -DskipTests` 再重启 |
| **mvn 与 mvnw 双仓库** | 同一份代码 install 后起后端行为还是旧的——PATH 的 `mvn` 读 `D:\DevTools\.m2`，`./mvnw` 读 `C:\Users\Lenovo\.m2`，jar 装错了仓 | install 与 `spring-boot:run` 用同一个 mvn；排查可用 `jcmd <PID> VM.system_properties \| grep class.path` 看实际加载的是哪个仓的 jar |
| **登录被防爆破锁定** | 连续 5 次错密码（用户名+IP 维度）后 15 分钟内凭据正确也拒，提示「操作过于频繁，请稍后再试」 | 等 TTL 自动恢复；或手动解锁：`docker exec ragent-redis redis-cli -a 123456 --no-auth-warning del "auth:login:lock:u:<用户名>"`；临时逃生阀：配置 `ragent.auth.brute-force.enabled: false` 后重启后端 |
| **残留 JVM** | 重启报 `Port 9090 was already in use` | `netstat -ano \| grep 9090` 找 PID → `taskkill //PID <pid> //F` |
| **MinerU 未配置** | PDF 解析不可用 | `MINERU_API_KEY` 未配；md/txt/csv 正常，暂用这些格式 |
| **ES 未 healthy 就起后端** | 启动直接失败：`ES 关键词共享索引创建失败`（关键词通道 fail-fast，与 PG/Redis 同口径） | 先 `docker compose ... up -d` 等 ragent-elasticsearch healthy 再起后端；图谱（LightRAG）/ 联网搜索仍默认关，在 `rag.graph.type` / `rag.search.channels` 开启 |
| **MCP Server 未启动** | 启动日志 `连接 MCP Server [default] 失败` | 可选 demo 服务（天气/票务等），不影响主链路；需要时再单独启动 `mcp-server` 模块 |
| **Trace / Langfuse 关闭** | 管理台无链路数据 | 默认关（省内存），需要可观测时再启用 |
| **SaaS 类跳转** | 登录页提示"注册账号" | 本 fork 已内置注册（§6），不再依赖管理员初始化 |

## 9. 已验证的能力边界（实测记录）

| 能力 | 状态 | 证据 |
|:---|:---|:---|
| mimo 主对话（流式 + 工具调用） | ✅ | SSE 收到 think + 正文增量，`finish_reason=tool_calls`，`search_knowledge` 3 事件 |
| 百炼 Embedding | ✅ | `text-embedding-v4` 554ms/条，PG 向量写入成功 |
| 百炼 Rerank | ✅ | `RerankPostProcessor 精排最高 0.96` |
| 端到端文档问答 | ✅ | 提问命中库内文档，答案准确复述端口等细节，带引用 |
| 知识库全链路 | ✅ | 建库 → 上传 → 分块入库 → 检索问答 |
| 注册 + BCrypt 密码 | ✅ | `POST /auth/register` 注册即登录，密码 `$2a` 存储，老 admin 首登自动升级 |
| 混合检索（向量 + 关键词双通道） | ✅ | 归因日志四件套：`启用的检索通道：[VectorSearch, KeywordSearch]` → `关键词检索完成，命中 1 库 1 条` → `融合池按通道: 向量=1 关键词=1` → `Rerank 输入/输出按通道 向量=1 关键词=1`（精排 0.98）；回填对账 ES `_count`=1 与 PG enabled chunk=1 一致；kill ES 后关键词通道隔离失败、纯向量通道照常出答案 |
| 关键词通道 fail-fast | ✅ | ES 不可达时后端启动即拒（`ES 关键词共享索引创建失败`），与 PG/Redis 同口径 |
| MCP 工具挂载 + 真实调用 | ✅ | mcp-server（ragent_bit 库，23 工具）启动后端日志 `返回 23 个工具`；意图树挂载 `current_date` 后 `/agent/v1/meta` capabilities 含 `mcp-tools`；Agent 对话真实触发 pending→running→done，返回当前日期 |
| 图谱 / 联网检索 | ⏸ 未启用 | 需 LightRAG / YDC 账号，见 §8 |
| PDF 解析（MinerU） | ⏸ 未配 Key | — |

## 10. 常用入口速查

| 入口 | 地址 |
|:---|:---|
| 前端 | http://localhost:5173 |
| 注册 | http://localhost:5173/register |
| 管理后台 | http://localhost:5173/admin |
| 后端 API | http://localhost:9090/api/ragent |
| PostgreSQL | 127.0.0.1:5432（postgres/postgres，库 `ragent`） |
| Elasticsearch | http://localhost:9200（关键词共享索引 `rag_keyword_store`） |
| rustfs 控制台 | http://localhost:9001 |
