-- DraftAgent RAG 文档与分块建表脚本（Batch 3.2 / V1 设计）
-- 目标库：draft_agent（请先手动创建数据库，脚本不包含任何账号密码）
-- 兼容 MySQL 5.7；ENGINE=InnoDB；字符集 utf8mb4。
--
-- 职责说明：
-- 1. document：文档元数据（归属、文件名、存储路径、状态、分块数等）。
-- 2. document_chunk：文档分块权威文本（用于引用、重建与审计）。
-- 3. 向量数据存放在 Qdrant，不落在 MySQL；本脚本不创建向量表。
-- 4. 不修改任何已有业务表（draft / conversation / agent_tool_execution 等）。
-- 5. 不引入逻辑删除字段；删除采用物理删除（document_chunk 通过外键级联删除）。
--
-- 归属与共享规则（Batch 3.2.1）：
-- - user_id：上传者用户ID，必填（BIGINT NOT NULL）。
-- - tenant_id：文档共享边界，可空（BIGINT NULL）。
--   * tenant_id 非空：同一租户所有成员共享该文档；
--   * tenant_id 为空：文档仅上传者本人可访问；
--   * 不同租户之间严格隔离。
-- - 删除 / 重新处理仅允许上传者本人操作。
-- 时间字段与现有表一致，均为 DATETIME（Java 侧使用 LocalDateTime）。
--
-- 说明：需要在目标 MySQL 数据库中手动执行一次；应用不会自动建表。
-- MySQL 5.7 不支持 CREATE TABLE ... 之外的幂等 ALTER，本脚本使用 CREATE TABLE IF NOT EXISTS。

-- -----------------------------
-- 文档表
-- -----------------------------
CREATE TABLE IF NOT EXISTS document (
    document_pk   BIGINT        NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    document_id   CHAR(36)      NOT NULL COMMENT '业务文档UUID（对外唯一）',
    user_id       BIGINT        NOT NULL COMMENT '上传者用户ID（必填）',
    tenant_id     BIGINT        NULL COMMENT '文档共享边界：非空时同租户成员共享；为空时仅上传者可见',
    filename      VARCHAR(255)  NOT NULL COMMENT '原始文件名',
    storage_path  VARCHAR(512)  NOT NULL COMMENT '实际存储路径（相对路径）',
    mime_type     VARCHAR(128)  NOT NULL COMMENT 'MIME 类型',
    file_size     BIGINT        NOT NULL DEFAULT 0 COMMENT '文件字节数',
    checksum      CHAR(64)      NULL COMMENT 'SHA-256 校验和（十六进制）',
    status        VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT '处理状态：PENDING/PROCESSING/READY/FAILED',
    chunk_count   INT           NOT NULL DEFAULT 0 COMMENT '分块数量',
    error_message VARCHAR(1000) NULL COMMENT '失败原因（脱敏，可空）',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（自动刷新）',
    PRIMARY KEY (document_pk),
    UNIQUE KEY uk_document_uuid (document_id),
    KEY idx_document_uploader_created (user_id, created_at),
    KEY idx_document_tenant_created (tenant_id, created_at),
    KEY idx_document_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='RAG文档元数据表';

-- -----------------------------
-- 文档分块表
-- -----------------------------
CREATE TABLE IF NOT EXISTS document_chunk (
    chunk_id    BIGINT      NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    document_pk BIGINT      NOT NULL COMMENT '关联 document.document_pk（内部主键）',
    document_id CHAR(36)    NOT NULL COMMENT '业务文档UUID（与 document.document_id 对应，冗余便于向量关联）',
    chunk_index INT         NOT NULL COMMENT '分块序号（从0开始）',
    content     MEDIUMTEXT  NOT NULL COMMENT '分块权威文本',
    char_count  INT         NOT NULL DEFAULT 0 COMMENT '字符数',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (chunk_id),
    UNIQUE KEY uk_document_chunk_index (document_pk, chunk_index),
    KEY idx_chunk_document_id (document_id),
    CONSTRAINT fk_chunk_document FOREIGN KEY (document_pk) REFERENCES document (document_pk) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='RAG文档分块表';
