# DraftAgent 项目阶段性交接文档（PROJECT_STATUS）

> 本文档以**当前本地实际源码**为准编写，明确区分「已实现」「已测试」「未验证」。
> 编写时间基准：最新提交 `69d503b`（main 分支，工作区干净）。
> 本文档不修改任何 Java 代码，也未执行 git commit / push。

---

## 1. 项目目标与整体架构

**定位**：基于 Spring Boot 模块化单体的「智能草案预订单管理平台」，目标是从传统 Java 业务系统逐步升级为 AI Agent / Multi-Agent 系统。

**架构原则**：
- 模块化单体（非微服务），单一可执行入口 `draft-agent-start`。
- 依赖单向、以 `common` 为基座；业务模块之间通过服务调用，不直接跨模块访问对方数据。
- 大模型仅通过「工具/服务接口」与业务交互，**不允许大模型直接访问数据库或直接修改业务状态**（下一阶段 Tool Calling 需遵守）。

**当前实际能力边界**：仅有工程骨架 + 基础 AI 对话（DeepSeek/OpenAI 兼容）；**业务 CRUD 未实现**，AI 仅有单轮对话，无 Tool Calling / RAG / Multi-Agent。

---

## 2. 六个 Maven 模块及依赖关系

| 模块 | 职责 | 当前实际状态 |
|---|---|---|
| `draft-agent-common` | 统一响应 `R<T>`、异常体系、全局异常处理 | ✅ 已实现 |
| `draft-agent-security` | 认证授权（Spring Security） | ⚠️ 仅骨架，`permitAll` 全放行 |
| `draft-agent-system` | 用户/角色/权限/租户 | ⚠️ 仅占位类，无实体/Mapper/Service |
| `draft-agent-business` | 草案/评审/决策/下单 | ⚠️ 仅 Demo（打印式）+ AOP 日志演示 |
| `draft-agent-ai` | Agent / 大模型接入 | ✅ 已实现 DeepSeek 单轮对话 |
| `draft-agent-start` | 启动入口与配置 | ✅ 已实现 |

**依赖关系（Maven）**：

```
draft-agent-start
├── draft-agent-common
├── draft-agent-security ──► draft-agent-system ──► draft-agent-common
├── draft-agent-system
├── draft-agent-business ──► draft-agent-system ──► draft-agent-common
└── draft-agent-ai       ──► draft-agent-common
```

说明：
- `system` 不再依赖 `security`（此前的反向依赖已修正）。
- `security` 依赖 `system`，便于后续 `UserDetailsService` 从 system 查询用户。
- `ai` 仅依赖 `common`（不反向依赖 start，不依赖 business）。
- **无循环依赖**。

---

## 3. 当前技术栈与版本

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | 父 POM `java.version=17` |
| Spring Boot | 3.4.12 | `spring-boot-starter-parent` |
| Spring AI | 1.0.9 | 父 POM `spring-ai-bom` 导入，`spring-ai-starter-model-openai` |
| MyBatis-Plus | 3.5.12 | `mybatis-plus-spring-boot3-starter`（system 模块） |
| MySQL 驱动 | 8.0.33 | `com.mysql:mysql-connector-j` |
| Redis | Spring Boot 管理版本 | `spring-boot-starter-data-redis`（security 模块） |
| Spring Security | Spring Boot 管理版本 | `spring-boot-starter-security` |
| 构建 | Maven | 多模块，`mvn clean install -DskipTests` 通过 |

**版本关键文件**：`pom.xml`（properties 与 dependencyManagement）、各子模块 `pom.xml`。

---

## 4. 已完成的 Java 基础设施

### 4.1 统一响应 `R<T>`
- 路径：`draft-agent-common/src/main/java/com/draftagent/common/core/domain/R.java`
- 字段：`code`、`msg`、`data`；静态工厂 `ok()/ok(data)/ok(msg,data)/fail()/fail(msg)/fail(code,msg)`。
- `@JsonInclude(NON_NULL)`；`isSuccess()` 已加 `@JsonIgnore`，不会污染 JSON。

### 4.2 异常体系
- `BaseException extends RuntimeException`（带 `int code`）：`draft-agent-common/.../exception/BaseException.java`
- `ServiceException extends BaseException`：`.../exception/ServiceException.java`
- 语义：业务异常携带 `code`，由全局处理映射为对应 HTTP 状态码。

### 4.3 全局异常处理 `GlobalExceptionHandler`
- 路径：`draft-agent-common/src/main/java/com/draftagent/common/core/exception/GlobalExceptionHandler.java`
- 已处理异常与 HTTP 状态映射：

| 异常 | HTTP 状态 | body.code |
|---|---|---|
| `BaseException` | `HttpStatus.resolve(code)`（无效则 500） | `code` |
| `MethodArgumentNotValidException` | 400 | 400 |
| `BindException` | 400 | 400 |
| `ConstraintViolationException` | 400 | 400 |
| `HttpMessageNotReadableException` | 400 | 400 |
| `HttpRequestMethodNotSupportedException` | 405 | 405 |
| `NoResourceFoundException` | 404 | 404 |
| `Exception`（兜底） | 500 | 500 |

- 关键决策：`handleBaseException` 返回 `ResponseEntity`，用 `HttpStatus.resolve(code)` 让 **HTTP 状态与 body.code 对齐**（支撑 503/502）。
- 兜底 `Exception` 记录完整堆栈到日志，**不向前端返回堆栈**。

### 4.4 健康检查
- `draft-agent-start/.../controller/HealthController.java`
- 接口：`GET /draft-agent/health`（context-path=`/draft-agent`）

### 4.5 Spring Security
- `draft-agent-security/.../config/SecurityConfig.java`
- 现状：`csrf disabled` + `anyRequest().permitAll()`，**无认证/无鉴权**（占位）。

### 4.6 业务 Demo（非真实业务）
- `draft-agent-business/.../service/DraftService.java`：`submitDraft/reviewDraft/queryDraft` 仅 `System.out`。
- `draft-agent-business/.../annotation/LogRecord.java` + `.../aspect/LogAspect.java`：`@LogRecord` 注解 + `@Before` 切面打印日志（演示性质）。

### 4.7 占位类
- `system/SystemModulePlaceholder.java`、`business/BusinessModulePlaceholder.java`、`ai/AiModulePlaceholder.java`（无引用，保留）。

---

## 5. Spring AI + DeepSeek 接入实现

### 5.1 依赖配置
- 父 `pom.xml`：`<spring-ai.version>1.0.9</spring-ai.version>` + `dependencyManagement` 导入 `org.springframework.ai:spring-ai-bom`（`type=pom, scope=import`）。
- `draft-agent-ai/pom.xml`：依赖 `org.springframework.ai:spring-ai-starter-model-openai`（版本由 BOM 管理，未单独指定）。

### 5.2 配置（`draft-agent-start/src/main/resources/application.yml`）
```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration
      - org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration
      - org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration
      - org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration
      - org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration
      - org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY:}          # 仅环境变量，默认空
      base-url: https://api.deepseek.com
      chat:
        options:
          model: deepseek-chat
```
- `server.port: 8080`，`context-path: /draft-agent`。
- `application-dev.yml`：仅 datasource（`DB_USERNAME/DB_PASSWORD` 占位）与 redis，与 AI 配置无冲突。

### 5.3 重要技术决策：为何排除官方自动配置
- 经反编译确认：`spring-ai-autoconfigure-model-openai-1.0.9` 注册了 6 个自动配置，默认均激活（`spring.ai.model.*`，`matchIfMissing=true`）。
- `OpenAiChatAutoConfiguration.openAiApi(...)` → `OpenAIAutoConfigurationUtil.resolveConnectionProperties(...)` 内 `Assert.hasText(apiKey)`，**无 Key 时启动即抛异常**。
- 因此「只给 AiConfig 加 `@Conditional`」无效，必须在 **application.yml 排除这些自动配置**，改为在 `AiConfig` 中按条件手动装配。

---

## 6. AI 模块组件设计

文件均在 `draft-agent-ai/src/main/java/com/draftagent/ai/`：

| 组件 | 路径 | 设计要点 |
|---|---|---|
| `ApiKeyPresentCondition` | `config/ApiKeyPresentCondition.java` | 实现 `Condition`，读取 `spring.ai.openai.api-key`，`StringUtils.hasText` 判非空才成立 |
| `AiConfig` | `config/AiConfig.java` | `@Configuration @Conditional(ApiKeyPresentCondition.class)`；仅在有 Key 时手动创建 `OpenAiApi`、`OpenAiChatModel`、`ChatClient` |
| `AiService` | `service/AiService.java` | `@Service`；`ObjectProvider<ChatClient>` 可选注入；封装调用并把上游异常翻译为 `ServiceException` |
| `AiController` | `controller/AiController.java` | `@RestController @RequestMapping("/ai")`；`POST /chat`，`@Valid` 校验，返回 `R<ChatResponse>` |
| `ChatRequest` | `dto/ChatRequest.java` | `@NotBlank(message="消息内容不能为空")` 的 `message` 字段 |
| `ChatResponse` | `dto/ChatResponse.java` | `reply` 字段 |

### 6.1 装配链与调用关系
```
AiController --依赖--> AiService --依赖(ObjectProvider)--> ChatClient
                                                         ^
AiConfig --@Conditional(有Key)--> OpenAiApi -> OpenAiChatModel -> ChatClient
```
- `OpenAiApi.builder().baseUrl(...).apiKey(String).restClientBuilder(RestClient.Builder).build()`（复用 Boot 的 RestClient.Builder，保留 Spring AI 重试/观测链路）。
- `OpenAiChatModel.builder().openAiApi(api).defaultOptions(OpenAiChatOptions.builder().model(model).build()).build()`（builder 内部默认填充 toolCallingManager/retry/observation）。
- `ChatClient.create(chatModel)`。
- Controller 不接触模型；Service 不含手写 HTTP。

---

## 7. 无 API Key 时的优雅降级方案

1. `application.yml` 排除 6 个 OpenAI 自动配置 → 无 Key 时不再触发 `Assert.hasText`。
2. `AiConfig` 受 `ApiKeyPresentCondition` 约束 → **无 Key 时不创建任何模型 Bean**（满足「不创建需要 Key 的 ChatModel」）。
3. `AiService` 用 `ObjectProvider<ChatClient>`：取不到 → 抛 `ServiceException(503, "AI 服务未配置（缺少 DEEPSEEK_API_KEY），暂不可用")`。
4. `GlobalExceptionHandler` 将 `code=503` 映射为 HTTP 503。
5. 结果：**无 Key 应用可正常启动，`/ai/chat` 返回 HTTP 503**，不伪造回复。

---

## 8. 异常处理与安全设计

### 8.1 AI 上游异常映射（`AiService`）
| 场景 | 捕获异常 | 抛出的业务异常 | HTTP |
|---|---|---|---|
| 未配置 Key | —（ChatClient 为 null） | `ServiceException(503)` | 503 |
| 上游认证失败(401/403) | `NonTransientAiException` | `ServiceException(502, 认证失败)` | 502 |
| 上游不可用/超时 | `TransientAiException` / `ResourceAccessException` | `ServiceException(502)` | 502 |
| 其它上游错误 | `RestClientException` | `ServiceException(502)` | 502 |

- `classifyUpstreamFailure` 通过异常消息文本包含 `401/403/authentication/unauthorized` 判定认证失败（**说明见「未解决问题」**）。

### 8.2 安全约束
- API Key 仅经 `${DEEPSEEK_API_KEY:}` 从环境变量读取；**未写入 yml/Java/Git**。
- 日志仅记录异常类名 + 通用中文提示，**不记录 `e.getMessage()`、请求头、Key**。
- 前端仅收到统一 `R` 与通用提示，不返回上游堆栈。
- `.gitignore` 已排除 `application-local.*`、`.env*` 等本地敏感配置。

---

## 9. 已完成的测试及真实测试结果

> 下列均为本地真实运行结果（Windows，JDK 17 / Maven 3.9，使用打包后的 jar，临时通过 `--server.port` 改端口）。

**构建**：`mvn clean install -DskipTests` → **BUILD SUCCESS**（7 反应堆模块全部 SUCCESS）。

**A. 不配置 `DEEPSEEK_API_KEY`** ✅ 已测试
- 启动成功：`Started DraftAgentApplication in ~4.7s`。
- `GET /draft-agent/health` → **HTTP 200**，`status=UP`。
- `POST /draft-agent/ai/chat`（`{"message":"hello"}`）→ **HTTP 503**，`{"code":503,"msg":"AI 服务未配置（缺少 DEEPSEEK_API_KEY），暂不可用"}`。

**B. 配置占位 Key（`sk-PLACEHOLDER-LEAKCHECK-ABCD1234`）** ✅ 已测试
- 启动成功。
- 空 message → **HTTP 400**，`msg=消息内容不能为空`。
- 有效 message → 真实请求打到 `api.deepseek.com`（触发 `RetryUtils` 重试）→ **HTTP 502**，`{"code":502,"msg":"上游 AI 服务认证失败，请检查 API Key 配置"}`。

**C. 日志泄露检查** ✅ 已测试
- 在应用日志中检索 Key 片段（`PLACEHOLDER-LEAKCHECK/ABCD1234/sk-PLACEHOLDER`）→ **未发现**。

**验证状态更新（2026-11-23）** ✅/⚠️
- 真实 DeepSeek 基础对话已成功（`/ai/chat` 返回 200）。
- `createDraft` Tool Calling 真实端到端已完成，草案成功落库（详见第 13.5 节）。
- 长响应/超时行为仍未专门验证。
- 流式输出、多轮会话仍未实现/未验证。

> 注：终端中文乱码为 Windows 控制台 GBK 显示问题；HTTP 响应体为正确 UTF-8。

---

## 10. Git 状态

- 当前分支：**main**
- 最新提交：**`69d503bbcf5d4fd6e9fc0b8a9d9cc110e950c312`**（信息：`加固`，作者 `jackhuang667-dev <jackhuang667-dev@users.noreply.github.com>`，时间 2026-11-23 00:28:11 +0800）
- 工作区：**干净（nothing to commit）**
- 与远程：`main` 与 `origin/main` 同步（up to date）。
- 远程：`origin https://github.com/jackhuang667-dev/DraftAgent.git`
- 近期提交：`69d503b 加固` → `b2491ee 加固` → `29270d0 refactor: clean up AOP test code` → `ed08e9c 加固` → `bd3b808 加固` → `d46f107 docs: 添加 README` → `5b440d8 init`

---

## 11. 尚未解决的问题 / 已知风险

1. **真实 Key 端到端（已更新 2026-11-23）**：真实 DeepSeek Tool Calling E2E 已完成，`createDraft` 成功落库（ID=4，状态 DRAFT）；原「未验证」限制已解除，详见第 13.5 节。
2. **上游异常分类依赖消息文本**：`classifyUpstreamFailure` 依据异常消息是否含 `401/403/authentication` 判定，若 DeepSeek 文案变化需调整；未解析响应体（有意避免敏感信息落日志）。
3. **排除范围较宽**：为保证无 Key 可启动，排除了全部 6 个 OpenAI 自动配置；将来如需 embedding/图像/语音等能力，需在 `AiConfig` 中另行按条件装配。
4. **`No MyBatis mapper was found` 警告**：system 模块尚无 mapper，`@MapperScan("com.draftagent.**.mapper")` 暂无目标，属已知现状。
5. **安全全放行**：`SecurityConfig` 为 `permitAll`，`/ai/chat` 当前无鉴权，任何调用方可触发 AI 调用（成本/滥用风险）。
6. **业务为 Demo**：`DraftService` 仅打印，非真实业务；`LogAspect` 使用 `System.out`。
7. **`AiModulePlaceholder` 冗余**：无引用的历史占位类，可后续清理。
8. **默认配置回退**：`AiConfig` 的 `base-url`/`model` 含默认值（`https://api.deepseek.com`、`deepseek-chat`），与 yml 配置重复但一致；修改时需两处同步意识。
9. **多模态/工具依赖**：未引入向量库、RAG、MCP；`spring-ai-starter-model-openai` 自带部分传递依赖未裁剪。

---

## 12. 下一阶段：Tool Calling 实施计划

**目标**：让模型从「单轮对话」升级为「可调用业务工具的 Agent」，严格遵守「模型不直接访问数据库、不直接改业务状态」的约束。

### 12.1 设计原则
- 工具 = 受控的「业务服务适配层」，工具内部调用 `business`/`system` 的 Service（经校验、鉴权、审计），模型只产出「工具调用意图」。
- 工具方法集中放在 `draft-agent-ai` 的 `tool` 包；业务实现仍在业务模块，ai 通过依赖注入调用（**需评估 ai → business 依赖方向**，建议通过接口/门面解耦，避免 ai 反向耦合 business 实现）。
- 所有工具调用需记录审计日志（谁、何时、调用哪个工具、参数摘要、结果状态）。

### 12.2 实施步骤
1. **依赖与装配**：确认 `spring-ai-starter-model-openai` 已提供 Tool Calling 所需 `ToolCallingManager`（`OpenAiChatModel` 已默认装配）。在 `AiConfig` 的 `ChatClient` 构建中注册默认工具（`ChatClient.builder(model).defaultTools(...)` 或将工具注入 `prompt().tools(...)`）。
2. **定义工具**：在 `com.draftagent.ai.tool` 下以 Spring AI `@Tool`（`org.springframework.ai.tool.annotation.Tool`）声明工具方法，例如：
   - `queryDraftSummary(draftId)`：只读查询草案摘要（调用 business 只读 Service）。
   - `listPendingReviews()`：分页列出待评审草案。
   - `submitDraft(...)`：写操作，需幂等 + 参数校验 + 权限校验，且必须在工具内部走业务流程（模型不直接改库）。
3. **调用链改造**：`AiService.chat(message)` 改为支持工具回调的多轮循环（Spring AI 内部处理「模型点单→程序执行→结果回传→模型总结」），保持异常翻译与降级逻辑不变。
4. **安全与防护**：
   - 工具白名单、参数强校验（`@ToolParam` + 业务层校验）。
   - 为 `/ai/chat` 增加鉴权（依赖 `draft-agent-security` 落地），防止匿名触发写工具。
   - 写工具加幂等键与审计；只读/写工具分权限。
5. **可观测性**：记录工具调用日志（脱敏），必要时接入 Micrometer Observation。
6. **测试计划**：
   - 单测：工具方法参数校验/权限/幂等。
   - 集成：真实 Key 下「模型选择工具—执行—总结」链路。
   - 降级回归：无 Key → 503；上游异常 → 502；确保不泄露敏感信息。

### 12.3 前置依赖（需先完成）
- 落地 `draft-agent-system` 的用户/权限（供工具鉴权）。
- 明确 `ai → business` 的依赖方向与接口边界（建议 `business` 暴露查询/命令接口，`ai` 仅依赖接口）。
- 为 `/ai/chat` 接入认证（`draft-agent-security`）。

---

## 附：关键文件路径速查

- 父 POM：`pom.xml`
- AI 依赖：`draft-agent-ai/pom.xml`
- 全局配置：`draft-agent-start/src/main/resources/application.yml`、`application-dev.yml`
- 启动类：`draft-agent-start/src/main/java/com/draftagent/DraftAgentApplication.java`
- 健康检查：`draft-agent-start/src/main/java/com/draftagent/controller/HealthController.java`
- 统一响应：`draft-agent-common/src/main/java/com/draftagent/common/core/domain/R.java`
- 异常处理：`draft-agent-common/src/main/java/com/draftagent/common/core/exception/GlobalExceptionHandler.java`
- 安全配置：`draft-agent-security/src/main/java/com/draftagent/security/config/SecurityConfig.java`
- AI 组件：`draft-agent-ai/src/main/java/com/draftagent/ai/{config,controller,service,dto}/`

---

## 13. 增量实施记录：createDraft Tool（第二轮 Tool Calling）

> 说明：本文件第 1–12 节为早期交接内容（以提交 `69d503b` 为基准），已与当前工作区不完全一致。
> 当前真实能力以本节及工作区代码为准：business 已具备草案持久化与读写接口，ai 已依赖 business 并注册两个工具。

### 13.1 本次功能
- 新增 `createDraft` Tool：用户自然语言提供标题与内容 → 模型选择 `createDraft` 工具 → 调用 `DraftService.createDraft(title, content)` 落库 MySQL → 返回 `draftId/title/status/createdAt`。
- 工具层仅做参数接收与委托，复用现有业务逻辑，不直接访问 Mapper；AI 不直接操作数据库。

### 13.2 修改文件（本次）
- 修改：`draft-agent-ai/src/main/java/com/draftagent/ai/tool/DraftTools.java`
  - 新增 `@Tool(name="createDraft")`，参数 `title`、`content` 均为 `@ToolParam(required=true)`。
  - 返回 `DraftCreateResponse`（复用现有 DTO，未新增 DTO）。
  - 类注释更新为「查询 + 创建」两种工具。
- 修改：`PROJECT_STATUS.md`（本记录）。
- 未改动：`AiService`、`AiConfig`、`DraftService`、`pom.xml`、安全配置、数据库配置。
- 工作区此前未提交的 business/ai 增量（持久化、DraftStatusView、DraftController 等）保留未回滚。

### 13.3 构建结果
- 命令：`mvn clean install -DskipTests`（Maven 3.9.2 / JDK 17.0.19）
- 结果：`EXIT_CODE=0`，7 个反应堆模块全部 `SUCCESS`，`BUILD SUCCESS`。

### 13.4 工具注册验证
- 使用 Spring AI `MethodToolCallbackProvider` 对 `new DraftTools(null)` 反射枚举工具（不触发业务调用）。
- 结果：`TOOL_COUNT=2`，识别到 `queryDraftStatus` 与 `createDraft`。
- `createDraft` 输入 schema：`required = [ "title", "content" ]`，两参数均为 string。

### 13.5 验证结果（真实 DeepSeek Tool Calling E2E，2026-11-23）
- **已完成真实 DeepSeek 自然语言 Tool Calling 端到端验证**：自然语言 → 模型选择 `createDraft` → `DraftService.createDraft` → MySQL 落库 → 回传草案 ID。
- 验证结果：`createDraft` 成功调用，MySQL 中草案 **ID=4**、标题 `Tool Calling真实验证-B8K2`、状态 `DRAFT`；其中 ID 与状态已通过 `GET /drafts/4/status` 复核（`found=true`、`status=DRAFT`）。
- 已验证：编译构建、工具 Bean/回调注册、schema 正确性、真实基础对话（`/ai/chat` 返回 200）、真实 `createDraft` 工具落库。
- 仍存在限制：无自动化测试覆盖；未专门验证高并发、超时、幂等等边界。

### 13.6 风险
1. **安全**：`/ai/chat` 仍 `permitAll` 无鉴权，新增写工具后等同开放匿名创建草案；对外暴露前必须接入认证/权限。
2. **重复创建**：模型重试或语义模糊可能多次调用，当前无幂等键；已在工具描述中约束「一次请求只调用一次」。
3. **参数编造**：模型可能编造或遗漏标题/内容；遗漏由业务校验拦截，编造内容无法自动识别。
4. **工具异常**：Spring AI 1.0.9 默认 `DefaultToolExecutionExceptionProcessor` 为 `alwaysThrow=false`（已核实字节码），异常预计以错误文本回传模型，但真实链路行为尚未实测。
5. **序列化**：工具结果经 Spring AI `JsonParser`（含 classpath Jackson 模块）序列化，`LocalDateTime` 预期可用，真实链路未测。

### 13.7 下一步
- 为 `/ai/chat` 及写接口接入认证/权限。
- 视需要为 createDraft 增加幂等与调用审计。
- 真实 Key 自然语言 Tool Calling 端到端回归已完成（2026-11-23）；后续补充自动化测试。
- 评审/决策/下单等工具暂不实现。

### 13.8 本地环境提示（不写入仓库凭据）
- 本地运行所需 `DB_PASSWORD`、`DEEPSEEK_API_KEY` 仅通过本地环境变量 / IDE 运行配置提供，真实值不写入 Git，也不记录在本文档。

### 13.9 安全状态（重要）
- `SecurityConfig` 当前为 `permitAll`：`/ai/chat`、`POST /drafts`、`GET /drafts/{id}/status` 均无认证/授权。
- `createDraft` 为写工具，匿名调用即可创建草案；当前配置**仅适合本地开发测试，不可直接暴露到公网**。
- 上线前必须接入认证/授权、限流与审计；写接口与写工具需单独授权。
- `DB_PASSWORD`、`DEEPSEEK_API_KEY` 仅存在于本地运行环境，不进入 Git；文档与源码均不含真实凭据。

---

## 14. Tool Calling 源码学习进度（交接记录，2026-11-24）

> 本节记录 Spring AI 1.0.9 Tool Calling 内部执行原理的调试进展与明日任务；不含任何真实凭据；不修改业务代码。

### 14.1 主题与已掌握概念
学习主题：Spring AI Tool Calling 内部执行原理。已理解：
- AiService：项目业务服务，入口 `AiService#chat(String)`（`draft-agent-ai/.../service/AiService.java:38`）。
- ChatClient：Spring AI 高级调用入口；`AiService` 中 `.tools(draftTools).call().content()`（AiService.java:46-48）。
- ChatModel：模型调用抽象（当前为 `OpenAiChatModel`）。
- ToolDefinition：工具名称、描述、参数 Schema。
- DeepSeek：根据消息与工具定义决定是否调用工具。
- ToolCall：模型返回的工具调用请求（位于 `AssistantMessage#getToolCalls()`）。
- ChatResponse：Spring AI 响应封装（`getResult()` / `getResults()`）。
- ToolCallingManager：协调工具调用（实现类 `DefaultToolCallingManager`）。
- ToolCallback：工具执行统一接口（实现类 `MethodToolCallback`）。

### 14.2 已完成的真实调试路径
1. 定位 `AiService.chat` 中的 `.call()`。
2. 进入 `DefaultChatClient$DefaultCallResponseSpec#content()`（反编译确认存在 `content()` 与 `chatResponse()`）。
3. 观察到真实 `ChatResponse` 对象。
4. 定位并在 `DefaultToolCallingManager#executeToolCalls(Prompt, ChatResponse)` 命中过断点。

### 14.3 真实测试结果（已完成）
- DeepSeek 调用 `createDraft` 成功。
- Java 业务逻辑执行成功，MySQL 已保存草案。
- 最近一次测试草案 **ID = 6**（用户提供；本轮未再次创建测试草案）。

### 14.4 观察到的现象与初步分析
- 在 `content()` 中观察到的 `ChatResponse`：`toolCalls=[]`、`textContent="草案已创建成功"`、`finishReason=STOP`。
  该对象是**工具循环结束后的最终响应**，因此不含 tool calls，属正常。
- 在 `executeToolCalls()` 断点处观察到 `chatResponse.toolCalls` 为空，与“进入该方法应代表首轮响应含工具调用”的预期不符，**原因待明日用调用栈确认**（可能是观察了错误的 ChatResponse 变量，或命中的是递归/流式路径）。
- **访问方式纠正**：`ChatResponse` 本身没有 `getToolCalls()`；工具调用位于
  `chatResponse.getResult().getOutput().getToolCalls()`（即 `AssistantMessage#getToolCalls()`）。
- **反编译确认的调用链**（Spring AI 1.0.9）：
  `OpenAiChatModel#call(Prompt)` → `OpenAiChatModel#internalCall(Prompt, ChatResponse)` →
  `toolCallingManager.executeToolCalls(prompt, chatResponse)`；
  若 `ToolExecutionResult#returnDirect()` 为 false，则基于 `ToolExecutionResult#conversationHistory()` 递归
  `internalCall(...)` 发起下一次模型调用。**工具循环在 ChatModel 内部完成**，因此 `ChatClient.content()` 只能看到最终响应。

### 14.5 明日任务（按顺序）
1. 阅读 `DefaultToolCallingManager#executeToolCalls()` 完整实现。
2. 用 IDEA Debug 调用栈确认调用者（预期 `OpenAiChatModel#internalCall`，可在 `OpenAiChatModel#call` 同时下断点）。
3. 定位第一次 `ChatModel.call()` 的返回位置（`OpenAiApi` 响应转换为 `ChatResponse` 处）。
4. 捕获包含 `createDraft` 的第一次 `ChatResponse`：在 `executeToolCalls` 入参处求值 `chatResponse.getResult().getOutput().getToolCalls()`。
5. 观察 `ToolCall` 的 `name`、`id`、`arguments`（`AssistantMessage.ToolCall`）。
6. 追踪 `ToolCallbackResolver#resolve(String)` 如何找到对应 `ToolCallback`。
7. 进入 `MethodToolCallback#call(String)`，观察 JSON 参数如何转换为 Java 参数并反射调用 `DraftTools#createDraft`。
8. 观察工具执行结果（`ToolExecutionResult`）如何回填 `conversationHistory` 并返回给模型生成最终文本。

建议断点：`OpenAiChatModel#call(Prompt)`、`OpenAiChatModel#internalCall(Prompt, ChatResponse)`、
`DefaultToolCallingManager#executeToolCalls(Prompt, ChatResponse)`、`ToolCallbackResolver#resolve(String)`、
`MethodToolCallback#call(String)`。

### 14.6 关键类/方法速查（Spring AI 1.0.9，均已核实存在）
- `com.draftagent.ai.service.AiService#chat(String)`
- `org.springframework.ai.chat.client.DefaultChatClient$DefaultCallResponseSpec#content()` / `#chatResponse()`
- `org.springframework.ai.chat.model.ChatResponse#getResult()` / `#getResults()`
- `org.springframework.ai.chat.messages.AssistantMessage#getToolCalls()`
- `org.springframework.ai.openai.OpenAiChatModel#call(Prompt)` / `#internalCall(Prompt, ChatResponse)`
- `org.springframework.ai.model.tool.DefaultToolCallingManager#executeToolCalls(Prompt, ChatResponse)`
- `org.springframework.ai.model.tool.ToolExecutionResult#returnDirect()` / `#conversationHistory()`
- `org.springframework.ai.tool.resolution.ToolCallbackResolver#resolve(String)`
- `org.springframework.ai.tool.method.MethodToolCallback#call(String)` / `#call(String, ToolContext)`
- 项目工具：`com.draftagent.ai.tool.DraftTools#createDraft(String,String)`、`#queryDraftStatus(Long)`

### 14.7 当前工作区提示（交接）
- HEAD：`50be8d7`（`feat(draft): add MySQL draft persistence and Spring AI tools`）。
- 交接时存在未提交改动（**勿丢失**，未 commit/push）：
  - `M draft-agent-common/.../exception/GlobalExceptionHandler.java`（新增 400/415 异常映射）。
  - `?? draft-agent-start/src/test/java/com/draftagent/exception/GlobalExceptionHandlerMockMvcTest.java`（6 个 MockMvc 测试，已通过）。
  - `M docs/http/test.http`（用户新增“断点调试测试”请求）。

### 14.8 约束（明日继续）
- 优先只读/断点调试；**不重复创建测试草案**（如确需新建，先与用户确认）。
- 不输出真实 API Key、数据库密码等敏感信息；不修改业务代码；不提交/推送 Git。

---

## 15. 增量进度（Batch 2.1 – 2.6，工作区实现，未提交）

> 本节记录工作区新增/修改的落地情况；均未 commit/push。真实 DeepSeek 端到端验证尚未执行。

- **Batch 1 / 2.4.1-early**：JDBC ChatMemory（`spring-ai-starter-model-chat-memory-repository-jdbc`，`MessageWindowChatMemory` maxMessages=20，`initialize-schema=never`），`docs/sql/spring_ai_chat_memory.sql`。
- **Batch 2.1-2.2**：`conversation` / `conversation_message` 业务表 DDL、Entity、Mapper（`docs/sql/conversation_schema.sql`）。
- **Batch 2.3**：`ConversationService`（T1 beginTurn / T2 completeTurn / T3 failTurn，事务内 `last_message_seq` 原子递增）。
- **Batch 2.4.1**：`agent_tool_execution` DDL/Entity/Mapper；**Batch 2.4.2**：`ToolExecutionService`（事务外幂等编排）+ `ToolExecutionTransactionService`（单事务：EXECUTING→DraftService.createDraft→SUCCESS，result_ref=draftId），`UNIQUE(tool_name, idempotency_key)`。
- **Batch 2.4.1（ChatMemory 手动化）**：移除 `MessageChatMemoryAdvisor`，改由 `AiService` 手动 `chatMemory.get/add`。
- **Batch 2.5**：`ChatRequest` 增 `requestId`；`AiService` 会话级进程内串行锁 + `beginTurn → ChatClient(tools + toolContext{requestId,conversationPk,turnId}) → ChatMemory → completeTurn`，异常时 best-effort `failTurn`；`DraftTools.createDraft` 接收 `ToolContext` 并委托 `ToolExecutionService`。
- **Batch 2.6（本次）**：
  - `AiConfig` 自定义 `ToolCallingManager`：`DefaultToolExecutionExceptionProcessor` 配置 `alwaysThrow=true` + `rethrowExceptions=[ServiceException]`，使**业务冲突原样抛出**（最终 HTTP 409），其他工具异常抛 `ToolExecutionException`。
  - `AiService` 捕获 `ToolExecutionException` → 脱敏 `ServiceException(502)`；业务 `ServiceException` 原样上抛。
  - `ToolExecutionService` 幂等复用新增 **`conversation_pk` 校验**：同 requestId 不同会话 → 409。
  - 新增测试：`ToolExceptionPropagationTest`（处理器原样复抛 409 + HTTP 409）、`ToolExecutionServiceMySqlIntegrationTest#sameRequestDifferentConversationIsConflict`。

**已知限制**：会话锁仅单 JVM；`SPRING_AI_CHAT_MEMORY` 与 `conversation` 业务表非跨存储原子事务；HTTP 请求整体不幂等（仅 createDraft 工具按 requestId 幂等）；真实 DeepSeek 端到端待人工执行。

---

## 16. 最新进度：Batch 2.7（JWT 认证与数据权限隔离，工作区实现，未提交）

> **新窗口衔接请优先阅读本节。** 第 1–14 节为历史内容，与当前代码已不完全一致；本节反映当前工作区真实状态。HEAD 仍为 `50be8d7`，大量改动尚未 commit/push。

### 16.1 当前 Git / 工作区（未提交、未 commit/push）
- 分支 `main`，HEAD `50be8d7`。
- 已修改（tracked）：`pom.xml`、`draft-agent-security/pom.xml`、`application.yml`、`SecurityConfig.java`、`DraftController.java`、`Draft.java`、`DraftService.java`、`AiService.java`、`DraftTools.java`、`AiConfig.java`、`ChatRequest.java`、`AiController.java`、`GlobalExceptionHandler.java`、`docs/http/test.http`、`PROJECT_STATUS.md`。
- 未跟踪（新增）：`draft-agent-common/.../security/`、`draft-agent-security/.../{jwt,user,web}/`、business 的 `Conversation/ConversationMessage/AgentToolExecution` 实体与 Mapper、`ConversationService`、`ToolExecutionService`、`ToolExecutionTransactionService`、`TurnContext`、`docs/sql/*.sql`、`draft-agent-start/src/test/` 全部测试。
- 约束：禁止 reset/restore/clean/commit/push；不得覆盖既有未提交文件。

### 16.2 Batch 2.7 内容（认证与隔离）
- 依赖：`io.jsonwebtoken:jjwt-api/impl/jackson:0.12.6`（security 模块，版本在根 `pom.xml` 的 `<jjwt.version>`）。
- 配置：`jwt.secret: ${JWT_SECRET:}`（`application.yml`）。
- 新增安全组件：`JwtService`（HS256 签发/校验，UTF-8 字节≥32 校验；未配置时进程内随机密钥并告警）、`JwtAuthenticationFilter`（Bearer → `CurrentUser` 写入 SecurityContext）、`RestAuthenticationEntryPoint`（401 JSON）、`RestAccessDeniedHandler`（403 JSON）、`SecurityCurrentUserProvider`；common 新增 `CurrentUser`、`CurrentUserProvider`。
- `SecurityConfig`：无状态（STATELESS），禁用 csrf/httpBasic/formLogin/logout；`/health,/error` permitAll；`/ai/**`、`/drafts/**` authenticated；其余 `anyRequest().permitAll()`。
- 身份链路：`CurrentUserProvider`（读 SecurityContext）→ `AiService` 取 `userId/tenantId` → `ConversationService.beginTurn(cid,msg,userId,tenantId)`；并写入 `ToolContext{requestId,conversationPk,turnId,userId,tenantId}`（模型无法覆盖身份）。
- 会话隔离：创建时绑定归属；继续对话 `beginTurn` 在读取 ChatMemory 之前校验归属，不符返回 403。
- 草案隔离：`Draft` 增 `user_id/tenant_id`；`DraftService.createDraft(...,userId,tenantId)` 绑定归属；`queryDraftStatus(...)` 归属不符返回 403；`DraftController` 使用当前用户；工具侧 `DraftTools` 从 ToolContext 取身份；幂等复用额外校验 `conversation_pk` 与草案归属。
- DDL：`docs/sql/draft_user_tenant.sql`（`draft` 增可空 `user_id/tenant_id` + `idx_draft_user/idx_draft_tenant`）**已在本地库执行一次**（未删除数据）。历史 `user_id IS NULL` 草案对已认证用户返回 403，如需访问需回填归属。

### 16.3 测试与安全审计
- `mvn -B test`：**Tests run: 45, Failures: 0, Errors: 0, Skipped: 0**（含真实 MySQL 的 ChatMemory/Conversation/ToolExecution/DataIsolation 测试）。
- 真实路径匹配（审计结论）：`/draft-agent/ai/chat` 命中 `/ai/**`；`/draft-agent/drafts` 与 `/draft-agent/drafts/{id}/status` 命中 `/drafts/**`；均 authenticated。**未发现认证绕过**。
- 待修复 Medium：①`anyRequest().permitAll()` 建议改 `denyAll()` 或显式 allowlist；②非 dev profile 缺 `JWT_SECRET` 应 fail-fast；③未校验 `iss/aud`；④无 token 吊销/刷新。

### 16.4 当前数据库表（均已存在）
`draft`（含 `user_id/tenant_id`）、`conversation`、`conversation_message`、`spring_ai_chat_memory`、`agent_tool_execution`。

### 16.5 本地环境 / 构建命令
- mvn 不在 PATH，用 IDEA 内置 Maven：
  `$env:JAVA_HOME="C:\Users\huanglongchen\.jdks\ms-17.0.19"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; & "C:\Program Files\JetBrains\IntelliJ IDEA 2023.2\plugins\maven\lib\maven3\bin\mvn.cmd" -B test`
- MySQL 5.7.43 / 库 `draft_agent`；`DB_USERNAME`（默认 root）/`DB_PASSWORD`/`JWT_SECRET`/`DEEPSEEK_API_KEY` 全部走环境变量，禁止写入仓库。

### 16.6 已知限制 / 下一步
- 会话锁仅单 JVM；`SPRING_AI_CHAT_MEMORY` 与业务表非跨存储原子事务；HTTP 请求整体不幂等（仅 createDraft 按 requestId 幂等）；无登录/注册，JWT 由受信任签发方生成（未暴露 HTTP 发号接口）。
- 建议下一步：先完成 16.3 的 4 项安全加固，再进入 RAG（RAG 必须沿用 `CurrentUser` 做 user/tenant 过滤）。
