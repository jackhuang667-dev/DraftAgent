-- Spring AI 1.0.9 官方 JDBC ChatMemory MySQL Schema
-- 目标库：draft_agent（请先手动创建数据库，脚本不包含任何账号密码）
--
-- 说明：
-- 1. 这是 Spring AI 内部的 ChatMemory 表，用于保存模型上下文窗口（MessageWindowChatMemory）。
-- 2. 不用于业务历史查询和工具审计；业务历史请使用独立的业务表。
-- 3. 需要在目标 MySQL 数据库中手动执行一次（应用已配置 initialize-schema=never，不会自动建表）。
-- 4. MySQL 字符集使用 utf8mb4（仅追加表级 ENGINE/CHARSET，不改动 Spring AI 官方字段与索引）。
-- 5. 列/索引与 Spring AI 1.0.9 官方 schema-mysql.sql 保持一致；不要擅自修改官方表结构。
--
-- 官方原始定义（供核对）：
--   conversation_id VARCHAR(36) NOT NULL
--   content         TEXT        NOT NULL
--   type            ENUM('USER','ASSISTANT','SYSTEM','TOOL') NOT NULL
--   timestamp       TIMESTAMP   NOT NULL
--   INDEX (conversation_id, timestamp)

CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    `conversation_id` VARCHAR(36) NOT NULL,
    `content` TEXT NOT NULL,
    `type` ENUM('USER', 'ASSISTANT', 'SYSTEM', 'TOOL') NOT NULL,
    `timestamp` TIMESTAMP NOT NULL,

    INDEX `SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX` (`conversation_id`, `timestamp`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='Spring AI 内部 ChatMemory 表（非业务历史）';
