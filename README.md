# DraftAgent

智能草案预订单管理平台 —— 基于 Spring Boot 模块化单体架构，目标是从传统 Java 业务系统逐步升级为 AI Agent 系统（Multi-Agent）。

## 技术栈

- JDK 17
- Spring Boot 3.4.12
- Maven
- MyBatis-Plus 3.5.12
- MySQL 8
- Redis 7
- Spring Security

## 模块结构

```
DraftAgent
├── draft-agent-common     公共基础模块（统一响应、全局异常处理）
├── draft-agent-security   认证授权模块（Spring Security）
├── draft-agent-system     系统管理模块（用户、角色、权限、租户）
├── draft-agent-business   业务模块（草案、评审、决策、下单）
├── draft-agent-ai         预留 Agent 模块（后续接入大模型）
└── draft-agent-start      项目启动模块
```

模块依赖关系：

```
draft-agent-start (启动入口)
├── draft-agent-common
├── draft-agent-security
├── draft-agent-system    ──► draft-agent-security ──► draft-agent-common
├── draft-agent-business  ──► draft-agent-system
└── draft-agent-ai        ──► draft-agent-common
```

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+
- MySQL 8、Redis 7（本地开发环境）

### 构建

```bash
mvn clean install -DskipTests
```

### 运行

```bash
mvn -pl draft-agent-start -am spring-boot:run
```

或使用 IDE 直接运行 `draft-agent-start` 模块的 `DraftAgentApplication`。

### 健康检查

应用启动后（默认端口 8080，context-path `/draft-agent`）：

```bash
curl http://localhost:8080/draft-agent/health
```

响应示例：

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "status": "UP",
    "service": "draft-agent",
    "timestamp": 1730000000000
  }
}
```

## 配置说明

- `application.yml`：公共配置（端口、MyBatis-Plus、日志等）
- `application-dev.yml`：开发环境配置（MySQL、Redis）

启动前请确认数据库 `draft_agent` 已创建，并调整 `application-dev.yml` 中的数据库连接信息。

## 开发状态

- [x] 工程骨架与模块划分
- [x] 统一响应 `R<T>` 与全局异常处理
- [x] 健康检查接口
- [ ] 认证授权（Spring Security 登录 / Token）
- [ ] 多租户能力
- [ ] 用户 / 角色 / 权限 CRUD
- [ ] 草案 / 评审 / 决策 / 下单业务流程
- [ ] AI Agent 接入（Spring AI / Multi-Agent）
