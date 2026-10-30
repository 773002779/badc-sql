-- 工单链路专用夹具（任务 7 起）
--
-- 独立于 seed/test-fixtures.sql：共享夹具加减人会打断 MigrationIT 的用户集合断言、
-- VisibilityServiceIT 与 GrantControllerIT 的归属断言（切片 1 已经被共享夹具的连带失效坑过一次）。
-- 本文件只由工单类 IT 加载。
--
--   * ops02（4，系统 1 主运维）：能审系统 1 的工单；
--   * lead01（5，系统 1 组长）：能终审 L3 工单；
--   * ops01 保持"系统 2 的运维"，正好用来验跨系统不能审。
--
-- 密码哈希沿用 test-fixtures.sql 的那一串（明文 Passw0rd!）。

INSERT INTO sys_user (id, login_name, password_hash, role, display_name) VALUES
    (4, 'ops02',  '$2b$10$TznpaUV2xFOaY3aN9B0lpe0H6sbVMVrDBekF4fxgICSl5BcU9SVGW', 'PRIMARY_OPS', '主运维二号'),
    (5, 'lead01', '$2b$10$TznpaUV2xFOaY3aN9B0lpe0H6sbVMVrDBekF4fxgICSl5BcU9SVGW', 'OPS_LEAD',    '运维组长');

INSERT INTO sys_system_operator (id, system_id, user_id, operator_type) VALUES
    (10, 1, 4, 'PRIMARY'),
    (11, 1, 5, 'LEAD');
