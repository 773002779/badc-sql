-- V2：系统运维归属（R16）
--
-- 计划要求"运维/组长按系统可见"，但 V1 里没有任何"谁负责哪个系统"的表：
-- sys_user.role 只说明角色，不说明归属系统。本迁移补上这张关系表。
--
-- 一个系统可以有多个运维（PRIMARY 唯一，BACKUP 可多个），组长（LEAD）按系统挂靠。

CREATE TABLE sys_system_operator (
    id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '归属 ID',
    system_id     BIGINT      NOT NULL COMMENT '系统 ID',
    user_id       BIGINT      NOT NULL COMMENT '运维用户 ID',
    operator_type VARCHAR(16) NOT NULL COMMENT 'PRIMARY|BACKUP|LEAD',
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_system_operator_system_user (system_id, user_id),
    KEY idx_system_operator_user (user_id),
    CONSTRAINT ck_system_operator_type
        CHECK (operator_type IN ('PRIMARY', 'BACKUP', 'LEAD'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '系统与运维人员的归属关系';
