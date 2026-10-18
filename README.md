<p align="center">
  <img src="assets/ragent-ai-banner.png" alt="Ragent AI">
</p>

# Ragent

[![CI](https://github.com/jiuwanl/ragent/actions/workflows/ci.yml/badge.svg)](https://github.com/jiuwanl/ragent/actions/workflows/ci.yml)

本地部署的 Agentic RAG 知识库平台：文档入库（解析 → 分块 → 向量化）→ 检索（默认向量单通道；关键词 / 图谱 / 联网为可选扩展）→ 带引用的智能问答，附 Agent ReAct 执行引擎与完整管理后台。前后端分离，普通开发机即可跑通。

> Fork 改造自 [nageoffer/ragent](https://github.com/nageoffer/ragent)（Apache-2.0），已移除原仓库推广内容，补充自助注册（BCrypt 密码）与[本地使用手册](docs/LOCAL-USAGE.md)。

## 界面预览

用户侧 v2 Agent 执行视图——模型思考、工具调用与回答按时间线实时展开：

![](assets/qa-home-v2.png)

回答支持 Markdown 渲染、来源引用、原文预览与追问推荐：

![](assets/qa-answer.png)

<details>
<summary><b>管理后台界面截图</b>（点击展开）</summary>

Agent 运行概览与核心指标：

![](assets/admin-overview-v2.png)

知识库与文档管理：

![](assets/admin-knowledge-base.png)

RAG Trace 链路追踪：

![](assets/admin-trace.png)

模型与系统设置：

![](assets/admin-settings.png)

</details>

## 快速开始

```bash
# 1. 中间件（Docker Desktop 需先手动启动）
docker compose -f resources/docker/local/local-dev.compose.yaml up -d

# 2. 后端（API Key 放 ~/.ragent-keys.env，先 source）
source ~/.ragent-keys.env && cd bootstrap && mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx2g"

# 3. 前端
cd frontend && npm run dev   # http://localhost:5173
```

完整步骤、模型 Key 配置、知识库建库/入库流程与已知坑点见 **[docs/LOCAL-USAGE.md](docs/LOCAL-USAGE.md)**。

- 种子管理员：`admin` / `admin`（首次登录后自动升级为 BCrypt 哈希）
- 自助注册：登录页「注册账号」或访问 `/register`，注册即登录

## 架构

双执行引擎（`ragent.engine.type`）：

- **v1 workflow**：固定编排管线——问题重写 → 意图识别 → 多路召回 → 组装 → 生成
- **v2 agent**：模型自主决策工具调用（知识检索 / MCP / Skills）的 ReAct 循环，当前主力模式

![](assets/ragent-framework-v2.png)

> 下图为 v2 核心链路，实际实现涉及更多细节与容错处理。

![](assets/ragent-chain-v4.png)

### 模块结构

前后端分离的模块化单体，后端按职责分为七个 Maven 模块：

| 模块 | 职责 |
|:---|:---|
| `infra-ai` | Chat / Embedding / Rerank / VLM 模型客户端、模型档位、路由、首包探测、健康状态与降级 |
| `system` | 用户认证（注册 / 登录 / BCrypt 密码）与审计日志 |
| `rag` | RAG 问答、知识库、入库 Pipeline、意图树、检索、会话及管理端 API |
| `agent` | AgentScope ReAct 执行引擎，接入知识检索与 MCP 工具，支持技能、记忆和人工确认 |
| `bootstrap` | 启动装配层，仅含启动类与主配置 |
| `framework` | 统一响应与异常、幂等、分布式 ID、MQ 适配、SSE 与跨节点流式取消等基础能力 |
| `mcp-server` | 基于 MCP Java SDK（Streamable HTTP `/mcp`）的独立工具服务，内置 23 个执行器（电商 / 企业 / 通用），独立数据库 `ragent_bit` |

![](assets/ragent-module-layering-v3.png)

## 技术要点

| 特性 | 说明 |
|:---|:---|
| **流量保护** | Redis ZSET 公平排队，结合 Lua 原子抢占、过期信号量和 Pub/Sub 唤醒 |
| **模型容错** | 多候选模型自动切换，首包超时、空响应或异常时触发降级；三态熔断隔离故障节点 |
| **检索稳定性** | 多通道并行检索，单通道失败不影响主链；按召回、Rerank、TopK 分层控制检索规模 |
| **数据一致性** | RocketMQ 事务消息保障分块可靠执行；支持幂等，远程刷新使用分布式锁和状态机 |
| **可观测与审计** | 记录 Trace Run / Node 的耗时、输入输出和异常；管理端提供趋势、详情及配置变更 Diff |
| **流式体验** | SSE 分事件输出思考、正文、来源和推荐问题；支持全局超时及客户端断开取消 |
| **会话与证据** | 消息持久化和摘要控制上下文；保存引用来源，支持原文预览、追问和反馈 |
| **Agent 执行保障** | 上下文按水位裁剪与压缩，长期记忆跨会话沉淀；写操作可配置人工确认，支持执行中断 |
| **安全基础** | Sa-Token 认证、BCrypt 密码存储、数据归属校验、上传限流、参数校验和统一异常处理 |

扩展点：模型（`ChatClient` / `EmbeddingClient` / `RerankClient`）、存储（向量库 / `ObjectStorageClient`）、检索（`SearchChannel` / 后处理器）、入库（`IngestionNode` / `DocumentFetcher`）、Agent（人设 + 技能 + 中间件）、MCP（工具规范 / 外部 Server）均通过接口与配置隔离接入。

## License

[Apache License 2.0](LICENSE)
