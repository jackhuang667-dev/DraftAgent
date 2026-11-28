-- DraftAgent 草案归属增量 DDL（Batch 2.7）
-- 目标库：draft_agent；兼容 MySQL 5.7.43；InnoDB/utf8mb4。
--
-- 目的：为 draft 表增加用户/租户归属，用于数据权限隔离。
-- 说明：
-- 1. 仅新增可空列与索引，不重建表、不清空数据、不影响既有草案。
-- 2. 既有草案 user_id/tenant_id 为 NULL；新代码下已认证用户访问归属为 NULL 的草案会被拒绝（403），
--    如需保留历史人工测试数据的可访问性，需人工回填归属后使用。
-- 3. MySQL 5.7 不支持 ADD COLUMN IF NOT EXISTS，请仅执行一次。

ALTER TABLE draft
    ADD COLUMN user_id   BIGINT NULL COMMENT '所属用户ID（可空，兼容历史数据）' AFTER status,
    ADD COLUMN tenant_id BIGINT NULL COMMENT '所属租户ID（可空，预留多租户）' AFTER user_id,
    ADD KEY idx_draft_user (user_id),
    ADD KEY idx_draft_tenant (tenant_id);
