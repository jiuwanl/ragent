# Ragent 简历叙事与面试弹药库

> 用途：把仓库里的实测证据翻译成简历语言。所有数字与日志均可在本仓库/CI 复现（见 §6 证据附录）。
> 使用原则：**只写你被追问到 L3（数据结构与失败场景）也能答上的内容**；诚实边界见 §4，越线即翻车。

## 1. 项目速览（放简历"项目经历"开头）

**Ragent**——生产级 Java Agentic RAG 知识库平台：文档解析→分块→向量化入库，向量+关键词（ES/ik）混合检索经 RRF 融合与 Rerank 精排，流式生成带引用回答；v2 Agent ReAct 引擎自主决策知识检索、23 个 MCP 工具与技能调用，带人工确认与三层记忆。

`Java 17` `Spring Boot 4.1` `PostgreSQL+pgvector` `Elasticsearch` `Redis/Redisson` `RocketMQ` `React 18/TS` `MCP` `GitHub Actions`——7 模块 Maven 父子工程，约 8 万行，默认层 655 个单测干净环境全绿，CI 三 Job 五连绿。

> Fork 深度二次开发自开源项目 [nageoffer/ragent](https://github.com/nageoffer/ragent)（Apache-2.0）。归属口径见 §4.2——**必须主动说明**，被发现比主动说伤害大一个量级。

## 2. 简历条目（两版，按目标年限选一）

### 2.1 偏落地（1–3 年）

- 基于开源 Ragent 深度二次开发：**自助注册 + BCrypt 密码体系**（含存量明文账号首登无感升级），注册即登录全链路 E2E 验证；
- 设计**登录防爆破闸门**：用户名+IP / IP 扫号双维度计数，Redis Lua 原子 `INCR`+首写 `EXPIRE`（两步写会留永不过期计数键的坑），统一锁定文案防用户名枚举，6 次错密码锁定→锁内正确密码也拒→TTL 恢复闭环实测；
- **混合检索从"默认关"到实测默认开**：ES 8.17 + ik 分词并入本地栈第 6 服务，排障修复官方客户端 9.x 与 8.17 服务端 `compatible-with=9` 头 400 不兼容；归因日志四件套验证向量+关键词双通道融合，kill ES 通道级降级不伤主链路；
- **测试治理 + CI 从 0 到绿**：JUnit `@Tag` 三层测试（默认层/集成层/live），干净环境 `mvn test` 655 测试全绿；GitHub Actions 三 Job（spotless+verify+jacoco / 真实 Redis 集成 / 前端 lint+tsc+build）五连绿；
- **应用容器化一键部署**：backend 多阶段镜像 + nginx SPA 反代（SSE 关缓冲），叠加 compose 复用中间件定义，排掉 brokerIP1 宿主机地址在容器内连自己的坑；
- 接入 springdoc OpenAPI：8 核心控制器分组注解，**默认全关**（`SPRINGDOC_ENABLED` 环境变量开门），鉴权方案对齐 Sa-Token 无 Bearer 前缀细节。

### 2.2 偏架构（3–5 年）

- 主导 Ragent 工程化改造：测试三层治理（655 默认层测试干净环境全绿，给"从未真实执行过的限流 Lua"补真实 Redis 集成测试）、CI 五连绿、防爆破/CORS 白名单/health 端点安全收敛、混合检索实测落地、容器化交付；
- 检索漏斗**预算不变式**：recall-budget(20) ≥ top-k(10)、rerank-candidate(40) ≥ top-k 启动期校验，通道级超时与失败隔离，RRF k=20 按候选池 20~40 条实测校准（非照抄论文 60）；
- **公平分布式限流**：Redisson `RPermitExpirableSemaphore` + Lua 原子 claim（僵尸许可 ZREM 回收）+ Pub/Sub 唤醒，FIFO 公平排队 548 行核心类，10 并发不超额实测；
- **模型三态熔断 + 首包探测降级**：ModelHealthStore 熔断态机与首包超时探测，流式中途切换备用模型不中断用户体验；
- RocketMQ **事务消息可靠切片** + 双层幂等（`@IdempotentSubmit` / `@IdempotentConsume`），跨节点 SSE 流式取消（TTL 上下文透传 + StreamTaskManager）。

> 两版共同底线：被问到任一卖点，必须能讲到 §3 对应卡片的 L3 层。答不上就不要写在简历上。

## 3. 六大卖点 STAR 卡片（含三层追问预案）

每张卡片：L1 是什么 → L2 为什么不用现成方案 → L3 数据结构与失败场景。L3 深挖弹药的完整版在 `docs/02-Core-Tech-and-DesignPatterns.md` §4（Q1–Q5），此处给骨架。

### 3.1 Redis 公平排队 + Lua 原子抢占

- **【STAR】** 多实例抢同一个限流许可，普通 INCR 计数要么超卖要么先到不先得。用 `RPermitExpirableSemaphore` 持许可、Lua 脚本原子"抢占+回收僵尸许可"，等待方 Pub/Sub 唤醒避免轮询空转，实现 FIFO 公平排队。
- **【代码锚点】** `framework/.../FairDistributedRateLimiter`（548 行）+ `lua/queue_claim_atomic.lua`
- **【L1】** 分布式公平限流器：同 key 请求按到达序排队，许可过期由下一个原子抢走。
- **【L2】** 为什么不用 Redisson 默认信号量？默认无公平序、无僵尸回收——持有者崩溃后许可悬空。为什么不用计数器+TTL？超卖窗口与不公平唤醒（见 docs/02 Q3）。
- **【L3】** Lua 单往返完成 `ZREM 过期成员 + ZADD 排队 + 尝试占位`，原子性防并发超卖；失败场景：进程崩溃→许可随 TTL 过期→下一排队者在同一次 Lua 里捡回；Pub/Sub 丢消息→兜底超时轮询。**实测**：集成层真实 Redis 验证 FIFO 次序、10 并发不超额、僵尸 ZREM 真实执行——这个脚本治理前从未被真实跑过，是主动补的测试债（面试故事点）。

### 3.2 三态熔断 + 首包探测降级

- **【STAR】** 云模型偶发慢/断，二态开关救不了"连上了但首包不来"的流式场景。设计 closed/open/half-open 三态健康度账本，叠加首包超时探测：流式期间首包未按时到即桥接切换备用模型，用户无感。
- **【代码锚点】** `infra-ai/.../ModelHealthStore` + `LlmFirstPacketProbe` / `ProbeStreamBridge`
- **【L1】** 按模型记健康状态，失败累积开路，冷却后放探测请求试探恢复。
- **【L2】** 为什么不用 Resilience4j？要按"模型实例×档位"粒度记账且与自有档位路由深度耦合，通用熔断器表达不了首包维度（docs/02 Q1）。
- **【L3】** 状态转移与失败判定矩阵（连接异常/超时/首包超时/内容异常各算什么）；half-open 只放一个探测请求防雪崩；切换时旧流已产出的增量如何去重不串号。

### 3.3 RocketMQ 事务消息 + 双层幂等

- **【STAR】** 文档分块"库记录与向量写入"跨系统一致性：半消息确保"业务成功才投递"，消费端幂等键 + 本地消息账防重复与乱序。
- **【代码锚点】** `framework/.../DelegatingTransactionListener` + `@IdempotentSubmit` / `@IdempotentConsume`；链路 `KnowledgeDocumentChunkConsumer`
- **【L1】** 事务消息：本地事务不成功就回滚半消息，成功才让 broker 投递。
- **【L2】** 为什么不用本地消息表轮询？时效差、补偿逻辑散落。为什么消息体自带幂等键还要消费幂等表？broker 重投是常态（docs/02 Q4）。
- **【L3】** 不丢：事务状态查找回滚/重投；不重：消费前置占坑（Redis/表唯一键），占坑成功执行失败要可重入；顺序：同 docId 路由同队列。**实测**：CI/本地全量测试绿；改造中排掉 mvn 双本地仓库导致"改了代码行为还是旧的"的经典坑（jcmd 查 classpath 实锤）。

### 3.4 检索预算不变式 + 通道级降级

- **【STAR】** 多通道检索各花各的预算会击穿 Rerank 上限：定义漏斗三段预算（召回 20 → 融合截断 40 → 精排取 10），启动期校验单调关系；单通道超时/异常隔离，主链路照常。
- **【代码锚点】** `MultiChannelRetrievalEngine` + `SearchChannelProperties`（预算校验）+ `KeywordSearchChannel`（失败隔离实例）
- **【L1】** 每通道召回配额、融合池上限、最终 topK 三层预算，配置自洽性启动即校验。
- **【L2】** 为什么不固定每通道条数×通道数？通道数是配置可变的，乘法会漂移；预算制让"加通道"不放大下游成本。
- **【L3】** 定向检索（意图命中库）与全库检索的预算分配、补充路比例兜底意图误判；失败场景：关键词通道 ES 宕机→ERROR 日志隔离、融合池只剩向量、回答照常——**实测**：kill ES 后归因日志 `融合池按通道: 向量=1`，260 delta 带引用回答正常产出（见 §6）。

### 3.5 向量写入装饰器同步副索引（W1 实测补强）

- **【STAR】** 向量、关键词、图谱三处索引必须同进同退，散写会漂移。装饰器包住向量存储服务，向量写入成功即同步 ES 关键词索引，删除/更新同理，主键统一 chunkId 保证跨模态去重。
- **【代码锚点】** `KeywordSyncingVectorStoreService` + `KeywordSyncVectorStorePostProcessor`
- **【L1】** 装饰器拦截向量写接口，追加副索引维护，业务无感。
- **【L2】** 为什么不用事务性写？ES 与 PG 无跨库事务；最终一致+幂等重放是务实解。
- **【L3】** 顺序：先向量后关键词，关键词失败整批报错触发 MQ 重消费重放（幂等靠 chunkId 定位覆盖）；存量文档回填：重跑分块接口全量重建（先删后建）。**实测**：`_count` 对账 ES=1 与 PG enabled chunk=1 一致；归因日志 `向量=1 关键词=1` 进融合池；ES 首启 fail-fast 建共享索引 `rag_keyword_store`（ik_max_word/ik_smart）。

### 3.6 跨节点 SSE 流式取消

- **【STAR】** 流式回答在 A 节点发起、用户可能在 B 节点点停止：取消信号跨节点可达。TTL（TransmittableThreadLocal）透传用户上下文过线程池，任务注册表持 taskId，停止接口广播取消，UI 事件流保证最后一包一致。
- **【代码锚点】** `framework/.../StreamTaskManager` + TTL 上下文 + `/rag/v3/stop`、`/agent/v1/stop`
- **【L1】** 每个流式任务领 taskId，停止按 taskId 找到执行线程发中断。
- **【L2】** 为什么不用 Thread.stop/超时兜底？前者已废弃，后者取消粒度太粗。
- **【L3】** TTL 保证子线程拿到调用方上下文（普通 ThreadLocal 跨池即丢）；失败场景：节点宕机→任务注册表 TTL 自然过期不泄漏；取消与完成竞态→幂等终态。**实测**：默认层单测 + SSE 端到端流式（268 delta）验证；本仓单节点，跨节点为设计保证（见 §4.3）。

## 4. 诚实边界（不能说的话）

### 4.1 不能夸大

- **不说生产/真实用户高并发**：全部证据来自单机 16GB 开发机的容器栈与 CI。说"生产级架构设计"，不说"生产环境验证"。
- **不说图谱/联网检索已实测**：代码具备、默认关，无基础设施。
- **不说多节点已验证**：SSE 取消/公平限流的跨节点能力是架构设计+单测保证，没有多机实测。
- **不说"655 个测试一直绿"**：治理前干净环境跑不全（10 个 live 测试混在默认层），是分层治理之后才全绿——这本身就是可以讲的故事。
- **覆盖率只报不闸**：jacoco 报告数字如实说（agent 63%/51% 指令/分支最高，rag 30%/25%，mcp-server 偏低），明确"只出报告不设红线"。

### 4.2 Fork 归属（必须主动说）

口径：**"基于开源项目 nageoffer/ragent 深度二次开发。我负责的部分：注册+BCrypt 密码体系、登录防爆破、混合检索实测启用（含 ES 客户端/服务端不兼容排障）、测试三层治理+CI 从 0 到五连绿、应用容器化、API 文档与安全收敛（CORS/health/防爆破）。"**

继承的六大卖点（§3）不是你写的也要讲到 L3——面试官默认"你讲得出细节就是你的理解深度"，但**不要说"我实现了"**，说"我深入理解并落地验证了"。主动打开仓库指代码位置是加分动作。

### 4.3 其他红线

- 不说"我配了 Ollama 本地模型"（本机铁律禁用，且无 GPU）。
- 不说 API Key 硬编码（全部环境变量/`~/.ragent-keys.env`，仓库内只有占位符）。
- 不把"修复上游 bug"说成"我写的代码的 bug"——ES 客户端不兼容是上游潜伏问题，正确口径："启用实测时发现官方客户端 9.x 与服务端 8.17 的兼容性坑，定位到 `compatible-with=9` 请求头并钉版本+换传输层修复"。

## 5. 面试反问素材（展示思考深度，选 6–8 条问面试官）

1. 团队的检索链路里，RRF/精排的预算怎么定的？有没有归因日志驱动的调参实践？
2. 模型网关层的熔断粒度是按模型、按租户还是按档位？首包超时这类"半死"流量怎么处理？
3. 多租户限流是公平排队还是优先级队列？持锁进程崩溃后的许可回收怎么做？
4. 测试分层怎么划的？live 测试挡在默认层外面之后，怎么防止"活测试"烂尾没人跑？
5. SSE/流式接口在网关层的缓冲与超时配置，团队踩过什么坑？
6. RAG 的引用可信度怎么保证？检索到但答错与检索不到，监控上怎么区分？
7. CI 绿多久算数？main 分支保护与 flaky 测试的处置纪律是什么？
8. 文档解析（如 MinerU 这类外部服务）失败率高时，入库管道的补偿策略是什么？

## 6. 证据附录（每个数字都可复现）

| 证据 | 复现方式 / 位置 |
|:---|:---|
| CI 五连绿（badge） | https://github.com/jiuwanl/ragent/actions —— run #1~#5 全绿；三 Job：backend（spotless+verify+jacoco）/ backend-integration（真实 Redis）/ frontend（lint+tsc+build） |
| 默认层 655 测试全绿 | `./mvnw -B test`（干净环境，无需中间件与 Key） |
| 集成层真实 Redis | `./mvnw -B test -Dgroups=integration -DexcludedGroups=live` |
| jacoco 覆盖率 | `./mvnw -B verify` → `*/target/site/jacoco/index.html`（agent 63%/51%、rag 30%/25%、framework 36%/40%、system 20%/21%、mcp-server 11%/9%、infra-ai 20%/17%——最近一次 verify） |
| 防爆破闭环 | LOCAL-USAGE §8；redis 键 `auth:login:fail:u:*` / `lock:u:*`；解锁三法（TTL/删键/逃生阀） |
| 混合检索归因四件套 | 容器/宿主任一：`启用的检索通道：[VectorSearch, KeywordSearch]` → `关键词检索完成，命中 1 库 1 条` → `融合池按通道: 向量=1 关键词=1` → `Rerank 输入按通道 向量=1 关键词=1`（精排 0.98） |
| 关键词回填对账 | `curl localhost:9200/rag_keyword_store/_count` = 1，与 PG `t_knowledge_chunk enabled=1` 一致 |
| 通道级降级 | `docker stop ragent-elasticsearch` 后再问：`ES 关键词检索失败` ERROR 隔离，`融合池按通道: 向量=1`，回答照常 260 delta |
| ES 客户端排障 | 9.4.2 客户端 `Accept: compatible-with=9` → ES 8.17 返 400（curl 可复现）；修复：根 pom 钉 `elasticsearch-java 8.17.0` + `RestClientTransport` |
| MCP 23 工具 | `cd mcp-server && mvn spring-boot:run`（ragent_bit 库，schema 在 `mcp-server/src/main/resources/db/`）→ 后端日志 `返回 23 个工具` → 意图树挂 `current_date` → `/agent/v1/meta` capabilities 含 `mcp-tools` → 对话真实调用 pending→running→done |
| 容器化一键部署 | `docker compose -f resources/docker/local/local-dev.compose.yaml -f resources/docker/app/app-compose.yaml up -d --build` → 8 容器 → `:8080` 登录问答 SSE 流式 → down/up 数据无损 |
| API 文档开关 | `SPRINGDOC_ENABLED=true` 起后端 → `/v3/api-docs` 200（8 分组+satoken scheme）；默认关时错误壳无数据 |
| mvn/mvnw 双仓库坑 | LOCAL-USAGE §5/§8；`jcmd <PID> VM.system_properties \| grep class.path` 排查法 |

---

*最后更新：2026-10。当前分支 main，CI badge：*
[![CI](https://github.com/jiuwanl/ragent/actions/workflows/ci.yml/badge.svg)](https://github.com/jiuwanl/ragent/actions/workflows/ci.yml)
