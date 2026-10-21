-- 集成测试共享夹具（R7）
--
--   * 用户：dev01（DEVELOPER）、ops01（PRIMARY_OPS），明文密码 Passw0rd!（BCrypt strength 10）
--   * 数据源 101 / 102 挂在同一模块 201 下，模块 201 已授权给 dev01
--   * 数据源 999 不属任何模块（用于越权 403 场景）
--
-- 由测试通过 @Sql("classpath:seed/test-fixtures.sql") 或 DatabasePopulator 在迁移后应用。

INSERT INTO sys_system (id, name) VALUES
    (1, '订单中心'),
    (2, '支付中心');

INSERT INTO sys_user (id, login_name, password_hash, role, display_name) VALUES
    (1, 'dev01', '$2b$10$TznpaUV2xFOaY3aN9B0lpe0H6sbVMVrDBekF4fxgICSl5BcU9SVGW', 'DEVELOPER', '研发一号'),
    (2, 'ops01', '$2b$10$TznpaUV2xFOaY3aN9B0lpe0H6sbVMVrDBekF4fxgICSl5BcU9SVGW', 'PRIMARY_OPS', '主运维一号');

INSERT INTO sys_module (id, system_id, name) VALUES
    (201, 1, '订单查询');

INSERT INTO datasource (id, system_id, name, db_name, db_type, host, port, charset, time_zone) VALUES
    (101, 1, '订单库-只读', 'order_db', 'MYSQL', '127.0.0.1', 3306, 'utf8mb4', '+00:00'),
    (102, 1, '订单归档库-只读', 'order_archive', 'MYSQL', '127.0.0.1', 3306, 'utf8mb4', '+00:00'),
    (999, 2, '支付库-只读', 'pay_db', 'MYSQL', '127.0.0.1', 3306, 'utf8mb4', '+00:00');

INSERT INTO module_datasource (id, module_id, datasource_id) VALUES
    (1, 201, 101),
    (2, 201, 102);

INSERT INTO user_module_grant (id, user_id, module_id, can_export, granted_by) VALUES
    (1, 1, 201, 1, 2);
