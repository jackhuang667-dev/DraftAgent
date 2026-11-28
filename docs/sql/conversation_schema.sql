-- DraftAgent 会话与消息业务表建表脚本（Batch 2.1 / V1 设计）
-- 目标库：draft_agent（请先手动创建数据库，脚本不包含任何账号密码）
-- 兼容 MySQL 5.7；ENGINE=InnoDB；字符集 utf8mb4。
--
-- 职责说明：
-- 1. conversation / conversation_message 是 DraftAgent 的「业务会话与完整历史」真源，
--    用于业务查询与后续工具审计。
-- 2. 与 Spring AI 的 SPRING_AI_CHAT_MEMORY（模型上下文窗口）职责分离：
--    - SPRING_AI_CHAT_MEMORY：仅保存模型上下文窗口，会被整窗重写，不用于业务查询。
--    - conversation_message：追加式完整历史，业务查询以此为准。
--    - 不要把 SPRING_AI_CHAT_MEMORY 改造成业务聊天历史表；本脚本也不修改它。
-- 3. 本脚本不修改现有 draft 表；不创建 Redis、用户表、租户表。
--
-- 关键标识说明（勿混淆）：
-- - conversation.conversation_id：CHAR(36) 业务 UUID，供对外接口/Spring AI conversationId 使用，唯一。
-- - conversation.id：BIGINT 内部主键（conversation_pk 关联它），仅用于库内关联与分页。
--   业务 UUID 与内部主键不是同一个概念。
--
-- 并发序号分配说明（重要）：
-- - conversation_message.sequence_no 的分配采用「事务内更新 conversation.last_message_seq 实现原子递增」，
--   例如：UPDATE conversation SET last_message_seq = last_message_seq + 1 WHERE id = ?;
--         SELECT last_message_seq FROM conversation WHERE id = ?;（同一事务内）
--   然后以取到的值作为本次 message 的 sequence_no。
-- - 严禁使用 SELECT MAX(sequence_no) + 1 作为并发序号分配方案（并发下会产生重复序号）；
--   sequence_no 的唯一性由 uk_conversation_sequence(conversation_pk, sequence_no) 兜底约束。

-- -----------------------------
-- 会话表
-- -----------------------------
CREATE TABLE IF NOT EXISTS conversation (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '内部主键（供 conversation_message.conversation_pk 关联）',
    conversation_id  CHAR(36)     NOT NULL COMMENT '业务会话UUID（对外使用，唯一），勿与内部主键混淆',
    user_id          BIGINT       NULL COMMENT '所属用户ID（可空，预留用户归属）',
    tenant_id        BIGINT       NULL COMMENT '所属租户ID（可空，预留租户归属）',
    title            VARCHAR(200) NULL COMMENT '会话标题（可空）',
    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT '会话状态，默认 ACTIVE',
    last_message_seq BIGINT       NOT NULL DEFAULT 0 COMMENT '会话内消息序号游标（事务内原子递增，用于分配 sequence_no）',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（自动刷新）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_conversation_uuid (conversation_id),
    KEY idx_user_updated (user_id, updated_at),
    KEY idx_tenant_updated (tenant_id, updated_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='会话表（业务会话主数据）';

-- -----------------------------
-- 会话消息表
-- -----------------------------
CREATE TABLE IF NOT EXISTS conversation_message (
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    conversation_pk BIGINT      NOT NULL COMMENT '关联 conversation.id（内部主键，非业务UUID）',
    turn_id         CHAR(36)    NOT NULL COMMENT '轮次UUID，标识一次用户请求及其关联回复',
    sequence_no     BIGINT      NOT NULL COMMENT '会话内消息顺序号（事务内基于 conversation.last_message_seq 原子递增分配）',
    role            VARCHAR(32) NOT NULL COMMENT '消息角色，预留 USER / ASSISTANT / TOOL / SYSTEM',
    message_type    VARCHAR(32) NOT NULL DEFAULT 'CHAT' COMMENT '消息类型，预留 CHAT / TOOL_CALL / TOOL_RESPONSE',
    message_status  VARCHAR(20) NOT NULL DEFAULT 'SUCCESS' COMMENT '消息状态，支持 PENDING / SUCCESS / FAILED',
    content         MEDIUMTEXT  NOT NULL COMMENT '消息正文/工具结果文本',
    error_code      VARCHAR(64) NULL COMMENT '失败时的错误码（可空）',
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（自动刷新）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_conversation_sequence (conversation_pk, sequence_no),
    KEY idx_conversation_turn (conversation_pk, turn_id),
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_pk) REFERENCES conversation (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='会话消息表（完整历史，含预留工具审计字段）';
