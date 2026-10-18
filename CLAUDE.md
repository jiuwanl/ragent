# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Ragent is a production-grade Java AI application platform for Agentic RAG (Retrieval-Augmented Generation), covering the full pipeline from document ingestion to intelligent Q&A. It supports hybrid retrieval (vector, keyword, knowledge graph, web search), intent recognition, MCP tool integration, and Agent-based ReAct execution.

## Tech Stack

- **Backend**: Java 17, Spring Boot 4.1.0, Maven multi-module
- **Frontend**: React 18, TypeScript, Vite, Tailwind CSS
- **Database**: PostgreSQL (via HikariCP + MyBatis-Plus)
- **Vector DB**: Milvus or PGVector (configurable)
- **Message Queue**: RocketMQ (for reliable chunking tasks)
- **Cache**: Redis with Redisson (distributed locking, fair queuing)
- **Authentication**: Sa-Token
- **MCP**: Model Context Protocol SDK 1.1.2

## Development Environment Constraints

**Hardware**: Windows laptop, 16GB RAM, no dedicated GPU.

**CRITICAL — Never use local models**: Do NOT enable, suggest, or run Ollama or any local LLM/Embedding/Rerank model. CPU inference on this hardware will freeze the system. All AI model calls (Chat, Embedding, Rerank, VLM) MUST use cloud APIs only.

In `application.yaml`, the `ollama` provider (`http://localhost:11434`) is **disabled by hardware constraint** (not by choice) — do not start Ollama.

Active cloud providers: `bailian` (DashScope — embedding/rerank + v1 tier candidates), `mimo` (primary agent chat), `aihubmix` / `deepseek` (placeholders without keys configured).

**Docker memory limits**: `resources/docker/local/local-dev.compose.yaml` sets per-container limits (512M/128M/384M/768M/256M, total 2048M) to fit 16GB RAM. Keep limits on any container you add; do not run all services simultaneously unless necessary.

## Build & Run Commands

### Backend (Maven)

```bash
# Full build (skip tests for speed)
mvn clean install -DskipTests

# Build specific module
mvn clean install -pl rag -DskipTests

# Run all tests (default layer: green on a clean machine, no middleware needed)
mvn test

# Run integration layer (needs local Redis; both -D properties are required together)
mvn test -Dgroups=integration -DexcludedGroups=live

# Run everything including live tests (needs full middleware stack + cloud API keys)
mvn test -DexcludedGroups=

# Coverage report (numbers only, no threshold gate)
mvn verify   # → */target/site/jacoco/index.html

# Run single test class
mvn test -pl rag -Dtest=RetrievalEngineTest

# Run single test method
mvn test -pl rag -Dtest=RetrievalEngineTest#testMultiChannelRetrieval

# Format code (Spotless - runs automatically on compile)
mvn spotless:apply
```

### Frontend

```bash
cd frontend

# Install dependencies
npm install

# Development server
npm run dev

# Build for production
npm run build

# Lint (ESLint, max-warnings 0)
npm run lint

# Format (Prettier)
npm run format
```

### Running the Application

```bash
# Start backend (bootstrap module)
mvn spring-boot:run -pl bootstrap

# Or run the JAR
java -jar bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar

# Start frontend dev server
cd frontend && npm run dev
```

The backend runs on port 9090 with context path `/api/ragent`. The frontend dev server proxies API calls to the backend.

## Architecture

### Module Structure

```
ragent/                    # Root POM (multi-module)
├── framework/             # Cross-cutting: response wrapper, exceptions, idempotency, distributed ID, MQ adapter, SSE, task cancellation
├── infra-ai/              # AI infrastructure: Chat/Embedding/Rerank/VLM clients, model tiers, routing, health check, circuit breaker
├── system/                # System support: user authentication (Sa-Token), audit logging
├── rag/                   # Core RAG engine: knowledge base, ingestion pipeline, intent tree, retrieval (vector/keyword/graph/web), session memory, prompt assembly
├── agent/                 # Agent execution: AgentScope ReAct engine, MCP tool bridge, skills, memory, human confirmation
├── bootstrap/             # Startup assembly: only Application class and config (no business logic)
└── mcp-server/            # Standalone MCP tool service (23 executors: e-commerce / enterprise / general; Streamable HTTP /mcp; own database ragent_bit)
```

### Execution Engines (v1 vs v2)

The system supports two execution modes configured via `ragent.engine.type`:

- **v1 (workflow)**: Pipeline orchestration with fixed steps: query rewrite → intent recognition → multi-channel retrieval → assembly → generation
- **v2 (agent)**: Agentic ReAct architecture where the model decides what tools to call (knowledge search, MCP tools, skills) in a loop

v2 is the primary architecture; v1 is retained for simpler Q&A scenarios.

### Key Design Patterns

| Pattern | Usage |
|---------|-------|
| Strategy | Search channels, result post-processors, document sources |
| Factory | Intent tree, chunking strategy, streaming callbacks |
| Template Method | Parallel retrieval, model requests |
| Registry | MCP tool discovery, intent node management |
| Decorator | Vector write sync (keyword + graph indexes) |
| Chain of Responsibility | Post-retrieval processing, model fault degradation |
| Middleware | Agent memory injection, context compression, skill masking |
| Adapter | Multi-provider model dialects, MCP tool bridging |
| State Machine | Remote document scheduled refresh |

### Data Flow

1. **Ingestion**: Document → Parser (Tika/MinerU) → Chunking → Embedding → Vector Store + Keyword Index + Graph Index
2. **Retrieval**: Query → Intent Classification → Knowledge Base Routing → Parallel Channel Recall → RRF Fusion → Rerank → TopK Selection
3. **Generation**: Context Assembly → Prompt → Streaming LLM Response → Citation Extraction → Source Attribution
4. **Agent Loop**: User Input → LLM Reasoning → Tool Selection → Execution → Observation → Continue/Stop

### Extension Points

- **Models**: Implement `ChatClient`/`EmbeddingClient`/`RerankClient`, add to candidate config
- **Storage**: Implement vector store or `ObjectStorageClient`, select via config
- **Retrieval**: Implement `SearchChannel` or post-processor, register as Spring Bean
- **Ingestion**: Implement `IngestionNode` or `DocumentFetcher`
- **Agent**: Create persona + skill manual, or register middleware
- **MCP**: Expose MCP tool spec, or configure external MCP Server URL

## Configuration

Key configuration is in `bootstrap/src/main/resources/application.yaml`:

- `ragent.engine.type`: `workflow` or `agent`
- `rag.vector.type`: `milvus` or `pg`
- `rag.keyword.type`: `none` or `es`
- `rag.graph.type`: `none` or `lightrag`
- `rag.storage.type`: `s3` or `oss`
- `ai.chat.candidates`: Model candidates with provider/model/supports-thinking
- `ai.chat.tiers`: Model tiers (fast/standard/deep) with timeout and candidate lists
- `rag.search.channels`: Enable/disable retrieval channels (vector/keyword/graph/web-search)

## Testing

- Tests use Spring Boot Test with Mockito; execution is layered with JUnit `@Tag` (root pom wires surefire `groups`/`excludedGroups`):
  - `live` = needs the full middleware stack + cloud API keys (10 unguarded `@SpringBootTest` classes + 3 env-guarded real-API tests) — excluded by default
  - `integration` = needs only local Redis (reads `TEST_REDIS_URL`, default `redis://:123456@127.0.0.1:6379`) — excluded by default
- Layer matrix: clean env / CI `mvn test` | everything `mvn test -DexcludedGroups=` | Redis layer `mvn test -Dgroups=integration -DexcludedGroups=live` (both properties must be passed together) | coverage `mvn verify`
- Agent tests cover: tool catalog, MCP bridge, memory pipeline, skill lifecycle, trace enrichment
- RAG tests cover: retrieval channels, chunking, ingestion pipeline, intent classification, prompt assembly
- Frontend has no unit tests; quality gates are `npm run lint` (max-warnings 0) + `npm run build`; `frontend/TESTING.md` is a manual integration-debug guide

## Code Conventions

- License header required on all Java files (Apache 2.0, enforced by Spotless)
- Lombok used extensively for boilerplate reduction
- MyBatis-Plus for database access
- All modules use `com.nageoffer.ai.ragent` base package
- SSE streaming for real-time responses
- TTL (TransmittableThreadLocal) for context propagation across thread pools
