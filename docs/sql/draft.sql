-- DraftAgent 草案表建表脚本
-- 目标库：draft_agent（请先手动创建数据库，脚本不包含任何账号密码）
-- 兼容 MySQL 5.7 / 8.0

CREATE TABLE IF NOT EXISTS draft (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    title      VARCHAR(255) NOT NULL COMMENT '草案标题',
    content    TEXT         NOT NULL COMMENT '草案内容',
    status     VARCHAR(32)  NOT NULL DEFAULT 'DRAFT' COMMENT '草案状态',
    created_at DATETIME     NOT NULL COMMENT '创建时间',
    updated_at DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='草案表';
