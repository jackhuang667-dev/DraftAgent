-- DraftAgent Batch 3.2.1 迁移：文档归属约束修正（企业共享 / 个人私有）
-- 目标库：draft_agent；兼容 MySQL 5.7；InnoDB / utf8mb4。
--
-- 最终规则：
-- 1. user_id BIGINT NOT NULL：记录上传者。
-- 2. tenant_id BIGINT NULL：文档共享边界。
--    * tenant_id 非空：同一租户所有成员共享；
--    * tenant_id 为空：仅上传者本人可访问；
--    * 不同租户严格隔离。
-- 3. 删除 / 重新处理仅允许上传者本人操作。
--
-- 重要（执行前检查）：
-- 本脚本会先输出统计与索引，然后执行 ALTER。只有确认 user_id 无 NULL 数据后才可继续；
-- 若存在 user_id IS NULL，请立即停止迁移并报告，禁止通过删除或修改数据绕过问题。
-- 本脚本仅对 Batch 3.2 旧结构执行一次（旧索引名 idx_document_user / idx_document_tenant）。

-- -----------------------------
-- 执行前只读检查
-- -----------------------------
SELECT COUNT(*) AS total_documents FROM document;
SELECT COUNT(*) AS null_user_id FROM document WHERE user_id IS NULL;
SELECT COUNT(*) AS null_tenant_id FROM document WHERE tenant_id IS NULL;
SELECT INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document'
 ORDER BY INDEX_NAME, SEQ_IN_INDEX;

-- -----------------------------
-- 迁移：user_id 设为 NOT NULL（tenant_id 保持可空，不做强制修改）
-- -----------------------------
ALTER TABLE document
    MODIFY COLUMN user_id BIGINT NOT NULL COMMENT '上传者用户ID（必填）';

-- -----------------------------
-- 迁移：统一索引命名（定义不变）并同步 tenant_id 注释（仍允许 NULL，不改变约束）
-- -----------------------------
ALTER TABLE document
    MODIFY COLUMN tenant_id BIGINT NULL COMMENT '文档共享边界：非空时同租户成员共享；为空时仅上传者可见',
    RENAME INDEX idx_document_user TO idx_document_uploader_created,
    RENAME INDEX idx_document_tenant TO idx_document_tenant_created;
