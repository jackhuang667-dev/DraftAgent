-- DraftAgent Agent 工具执行记录表建表脚本（Batch 2.4.1 / V1 设计）
-- 目标库：draft_agent（请先手动创建数据库，脚本不包含任何账号密码）
-- 兼容 MySQL 5.7.43；ENGINE=InnoDB；字符集 utf8mb4。
--
-- 职责说明：
-- 1. 记录 Agent 工具执行（幂等 + 审计），是工具执行的结果真源之一。
-- 2. 不替代 conversation_message 的完整对话历史；不修改 draft / SPRING_AI_CHAT_MEMORY。
-- 3. 幂等：UNIQUE(tool_name, idempotency_key)，防止同一逻辑操作重复执行产生重复副作用。
-- 4. arguments 使用 JSON 类型保存脱敏后的工具入参；result_ref 保存结果引用（如 draftId），
--    避免与具体工具的返回结构耦合。
--
-- 说明：需要在目标 MySQL 数据库中手动执行一次；应用不会自动建表。

CREATE TABLE IF NOT EXISTS agent_tool_execution (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    execution_id    CHAR(36)     NOT NULL COMMENT '本次执行记录UUID（应用生成，唯一）',
    conversation_pk BIGINT       NOT NULL COMMENT '关联 conversation.id（内部主键）',
    turn_id         CHAR(36)     NOT NULL COMMENT '关联轮次UUID',
    idempotency_key VARCHAR(36)  NOT NULL COMMENT '逻辑操作幂等键（跨重试稳定）',
    tool_call_id    VARCHAR(100) NULL COMMENT '模型侧 tool_call id（可空，仅观测用，不稳定）',
    tool_name       VARCHAR(100) NOT NULL COMMENT '工具名称',
    `arguments`     JSON         NOT NULL COMMENT '工具入参（脱敏后的JSON）',
    `status`        VARCHAR(20)  NOT NULL DEFAULT 'EXECUTING' COMMENT '执行状态：EXECUTING/SUCCESS/FAILED/UNKNOWN',
    result_ref      VARCHAR(128) NULL COMMENT '结果引用（如 draftId）',
    error_code      VARCHAR(64)  NULL COMMENT '失败错误码',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（自动刷新）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_execution_id (execution_id),
    UNIQUE KEY uk_tool_idem (tool_name, idempotency_key),
    KEY idx_conversation_turn (conversation_pk, turn_id),
    KEY idx_status_updated (`status`, updated_at),
    CONSTRAINT fk_tool_execution_conversation FOREIGN KEY (conversation_pk) REFERENCES conversation (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='Agent工具执行记录（幂等+审计）';
