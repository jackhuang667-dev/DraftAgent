# DraftAgent

智能草案预订单管理平台 —— 基于 Spring Boot 模块化单体架构，并逐步演进为带 Tool Calling 与 RAG 能力的 AI Agent 系统。

## 技术栈

- JDK 17
- Spring Boot 3.4.12
- Maven
- Spring AI 1.0.9（DeepSeek Chat、SiliconFlow Embedding、PDF Reader）
- MyBatis-Plus 3.5.12
- MySQL 5.7
- Spring Security + JWT（jjwt 0.12.6）
- Qdrant 1.12.4（REST 6333 / gRPC 6334）
- Redis（依赖保留，当前业务未强依赖）

## 模块结构

```
DraftAgent
├── draft-agent-common     公共基础（R<T>、异常体系、全局异常处理、CurrentUser）
├── draft-agent-security   认证授权（Spring Security + JWT）
├── draft-agent-system     系统管理（用户、角色、权限、租户占位）
├── draft-agent-business   业务（草案、会话、消息、工具执行、文档/分块）
├── draft-agent-ai         AI（DeepSeek Chat、Tool Calling、文档解析、Embedding）
└── draft-agent-start      启动入口与配置
```

依赖关系（单向、无循环）：

```
draft-agent-start
├── draft-agent-common
├── draft-agent-security ──► draft-agent-system ──► draft-agent-common
├── draft-agent-system
├── draft-agent-business ──► draft-agent-system ──► draft-agent-common
└── draft-agent-ai       ──► draft-agent-business ──► draft-agent-system ──► draft-agent-common
```

> `business` 不依赖 `ai`：文档解析通过 `business` 定义的接口端口，由 `ai` 实现。

## 已实现能力

- JWT 无状态认证：`/ai/**`、`/drafts/**`、`/documents/**` 需认证；`/health`、`/error` 放行；其余 `denyAll`。
- 用户/租户数据隔离：身份仅来自服务端 `CurrentUser`；`tenant_id` 非空为租户共享，空为个人私有。
- 会话与消息持久化（`conversation` / `conversation_message`）+ Spring AI JDBC ChatMemory。
- Tool Calling：`createDraft`（幂等 + 审计，业务冲突返回 409）。
- 文档上传：TXT / Markdown / PDF → 解析 → `TokenTextSplitter` 分块 → `document_chunk` 持久化（本地文件存储）。
- 独立 EmbeddingModel：SiliconFlow `BAAI/bge-m3`（1024 维），与 DeepSeek Chat 完全隔离；未配置 Key 时不创建该 Bean。
- Qdrant Collection 初始化：`draftagent_chunks_v1`（size=1024、Cosine）+ Payload Index（`tenant_id`、`user_id`、`document_id`）。
- Chunk 向量入库：批量 Embedding（32/批）→ 批量 Upsert 到 Qdrant，Point ID = `chunk_id`，Payload 携带 `document_id/document_pk/chunk_id/chunk_index/user_id/tenant_id`（个人文档 `tenant_id=null` 显式写入）。
- 向量检索（Batch 3.5.1）：查询 Embedding → Qdrant Top-K（企业 `tenant_id` / 个人 `tenant_id IS NULL AND user_id`），仅返回 Chunk 元数据与原始 score。
- MySQL 原文回查（Batch 3.5.2）：按 `chunk_id` 一次批量 JOIN `document` / `document_chunk`，复核 READY 与 user/tenant 归属，交叉核对 Qdrant 元数据，并按原始排名返回原文 + score。
- RAG 问答（Batch 3.5.3）：组装 System/User Prompt（含 Prompt Injection 防护）→ 复用现有 DeepSeek ChatClient 生成回答 → 后端返回真实 Chunk 引用来源；无检索结果时不调用模型；不挂 ChatMemory、不触发 Tools。
- RAG 上下文预算与引用校验（Batch 3.5.4）：按 Qdrant 排名前缀选择 Chunk，字符预算内不截断、不跳选；校验模型回答中的 `[n]`，仅保留指向实际进入 Prompt 的引用，无效编号移除且不重映射。

> 注意：文档上传在解析分块后会调用 Embedding 并写入 Qdrant。未配置 `EMBEDDING_API_KEY` 或 Qdrant 不可用时，上传会失败（503）并将文档标记为 FAILED；应用本身仍可正常启动。

**尚未实现**：RAG HTTP 问答接口与真实模型端到端验收、文档列表/下载/删除/重新处理、登录/注册发号、Multi-Agent。

## 快速开始

### 环境要求

- JDK 17+、Maven 3.8+
- MySQL 5.7+（本地库 `draft_agent`）
- Qdrant 1.12.x（Docker，gRPC 6334）
- （可选）Redis

### 环境变量（不要写入仓库）

```bash
DB_USERNAME=root
DB_PASSWORD=...
DEEPSEEK_API_KEY=...          # Chat
JWT_SECRET=...                # 非 dev 必填，UTF-8 至少 32 字节

EMBEDDING_API_KEY=...         # 文档向量化必需；未设置则不创建 EmbeddingModel（上传将失败）
EMBEDDING_BASE_URL=https://api.siliconflow.cn
EMBEDDING_MODEL=BAAI/bge-m3
EMBEDDING_DIMENSIONS=1024

QDRANT_HOST=localhost
QDRANT_GRPC_PORT=6334
QDRANT_COLLECTION=draftagent_chunks_v1
QDRANT_DIMENSIONS=1024
QDRANT_INIT_ON_STARTUP=false  # true 时启动时初始化 Collection（失败不阻断启动）

RAG_RETRIEVAL_TOP_K=5         # 向量检索默认 Top-K
RAG_RETRIEVAL_MAX_TOP_K=20    # 向量检索允许的硬上限
RAG_RETRIEVAL_MAX_CONTEXT_CHARS=12000  # 进入 Prompt 的参考资料字符预算
```

### 初始化数据库

按顺序在 `draft_agent` 库执行 `docs/sql/` 脚本（MySQL 5.7）：

1. `draft.sql`
2. `draft_user_tenant.sql`（为 `draft` 增加用户/租户归属）
3. `conversation_schema.sql`
4. `agent_tool_execution.sql`
5. `spring_ai_chat_memory.sql`
6. `document_schema.sql`

> 若 `document` 表是 Batch 3.2 旧结构，改用 `document_tenant_shared_v1.sql` 迁移。

### 启动 Qdrant

```bash
docker run -d --name draftagent-qdrant \
  -p 6333:6333 -p 6334:6334 \
  -v "${PWD}/data/qdrant:/qdrant/storage" \
  qdrant/qdrant:v1.12.4
```

Collection 默认不在启动时自动创建；可将 `QDRANT_INIT_ON_STARTUP=true` 启动一次，或调用 `QdrantCollectionInitializer`。

### 构建与测试

```bash
mvn clean install -DskipTests
mvn -B test
```

### 运行

```bash
mvn -pl draft-agent-start -am spring-boot:run
```

或使用 IDE 运行 `draft-agent-start` 的 `DraftAgentApplication`。

## 主要接口

应用默认端口 8080，context-path `/draft-agent`。

| 接口 | 说明 |
|---|---|
| `GET /draft-agent/health` | 健康检查（公开） |
| `POST /draft-agent/ai/chat` | AI 对话（需认证；`conversationId`、`requestId`、`message`） |
| `POST /draft-agent/drafts` | 创建草案（需认证） |
| `GET /draft-agent/drafts/{id}/status` | 查询草案状态（需认证，归属校验） |
| `POST /draft-agent/documents` | 上传文档（multipart `file`，需认证） |

请求示例见 `docs/http/test.http`。

## 配置说明

- `application.yml`：公共配置、Spring AI、JWT、`rag.*`（文档/Embedding/Qdrant）。
- `application-dev.yml`：本地 MySQL / Redis。
- 所有密钥均通过环境变量读取，仓库不包含真实凭据。

## 开发状态

- [x] 工程骨架与模块划分
- [x] 统一响应 `R<T>` 与全局异常处理
- [x] 健康检查
- [x] JWT 认证与用户/租户数据隔离
- [x] 会话/消息持久化 + ChatMemory
- [x] Tool Calling（createDraft，幂等 + 审计）
- [x] 文档上传 / 解析 / 分块持久化
- [x] 独立 EmbeddingModel（SiliconFlow bge-m3）
- [x] Qdrant Collection 与 Payload Index 初始化
- [x] 向量写入与 Chunk Payload 映射（批量 Embedding + Qdrant Upsert，失败补偿）
- [ ] RAG 检索与引用回答
- [ ] 文档列表 / 下载 / 删除 / 重新处理
- [ ] 用户 / 角色 / 权限 CRUD 与登录发号
- [ ] Multi-Agent
