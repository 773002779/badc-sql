-- 平台元数据库基线（V1）
--
-- 约定：
--   * 时间列统一以 UTC 存储，类型 datetime(3)，不做时区转换。
--   * 字符集统一 utf8mb4 / utf8mb4_0900_ai_ci。
--   * 审计表 append-only 由 DAO 层保证（本切片单 schema，不做表级权限隔离，R9），此处只建表。
--   * 表间关系用索引保障查询，不建外键：数据源/模块存在下线场景，外键会阻塞后续任务的删除路径。

CREATE TABLE sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户 ID',
    login_name    VARCHAR(64)  NOT NULL COMMENT '登录名',
    password_hash VARCHAR(60)  NOT NULL COMMENT 'BCrypt 密码哈希（strength 10）',
    role          VARCHAR(32)  NOT NULL DEFAULT 'DEVELOPER'
                  COMMENT '角色：DEVELOPER|PRIMARY_OPS|BACKUP_OPS|OPS_LEAD|ADMIN',
    display_name  VARCHAR(64)  NULL COMMENT '显示名',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_login_name (login_name),
    CONSTRAINT ck_sys_user_role
        CHECK (role IN ('DEVELOPER', 'PRIMARY_OPS', 'BACKUP_OPS', 'OPS_LEAD', 'ADMIN'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '平台用户';

CREATE TABLE sys_system (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '系统 ID',
    name       VARCHAR(128) NOT NULL COMMENT '系统名称',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_system_name (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '业务系统';

CREATE TABLE sys_module (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '模块 ID',
    system_id  BIGINT       NOT NULL COMMENT '归属系统',
    name       VARCHAR(128) NOT NULL COMMENT '模块名称',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_module_system_name (system_id, name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '功能模块（研发授权的最小单位）';

CREATE TABLE datasource (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '数据源 ID',
    system_id         BIGINT       NOT NULL COMMENT '归属系统（一库一系统）',
    name              VARCHAR(128) NOT NULL COMMENT '数据源名称',
    db_name           VARCHAR(128) NOT NULL COMMENT '库名（对研发可见）',
    db_type           VARCHAR(32)  NOT NULL DEFAULT 'MYSQL' COMMENT '数据库类型，本切片为 MYSQL',
    host              VARCHAR(255) NOT NULL COMMENT '主机（仅运维可见）',
    port              INT          NOT NULL COMMENT '端口（仅运维可见）',
    charset           VARCHAR(32)  NOT NULL DEFAULT 'utf8mb4' COMMENT '连接字符集',
    time_zone         VARCHAR(64)  NOT NULL DEFAULT '+00:00' COMMENT '连接时区（统一 UTC）',
    capabilities_json JSON         NULL COMMENT '能力探测结果',
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_datasource_system_name (system_id, name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '数据源';

CREATE TABLE conn_account (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '账号 ID',
    datasource_id   BIGINT       NOT NULL COMMENT '归属数据源',
    account_type    VARCHAR(16)  NOT NULL COMMENT 'readonly|dml|ddl|onlineddl',
    username        VARCHAR(128) NOT NULL COMMENT '数据库用户名',
    password_cipher TEXT         NOT NULL COMMENT 'AES-256-GCM 密文：base64(iv||ciphertext||tag)',
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_conn_account_datasource_type (datasource_id, account_type),
    CONSTRAINT ck_conn_account_type
        CHECK (account_type IN ('readonly', 'dml', 'ddl', 'onlineddl'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '数据源连接账号（凭证加密存储）';

CREATE TABLE module_datasource (
    id            BIGINT NOT NULL AUTO_INCREMENT COMMENT '关联 ID',
    module_id     BIGINT NOT NULL COMMENT '模块 ID',
    datasource_id BIGINT NOT NULL COMMENT '数据源 ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_module_datasource (module_id, datasource_id),
    KEY idx_module_datasource_datasource (datasource_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '模块与数据源的归属关系';

CREATE TABLE user_module_grant (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '授权 ID',
    user_id    BIGINT      NOT NULL COMMENT '被授权用户',
    module_id  BIGINT      NOT NULL COMMENT '授权模块',
    can_export TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否允许导出',
    granted_by BIGINT      NULL COMMENT '授权人用户 ID',
    granted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '授权时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_module_grant (user_id, module_id),
    KEY idx_user_module_grant_module (module_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '用户-模块授权';

CREATE TABLE query_history (
    id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '历史 ID',
    user_id       BIGINT      NOT NULL COMMENT '发起用户',
    datasource_id BIGINT      NOT NULL COMMENT '目标数据源',
    sql_text      MEDIUMTEXT  NOT NULL COMMENT '原始 SQL',
    row_count     INT         NULL COMMENT '返回行数（失败时为空）',
    elapsed_ms    BIGINT      NULL COMMENT '耗时毫秒（失败时为空）',
    status        VARCHAR(32) NOT NULL COMMENT '执行状态',
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_query_history_user_created (user_id, created_at),
    KEY idx_query_history_datasource (datasource_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '查询历史';

CREATE TABLE audit_event (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '事件 ID',
    seq           BIGINT       NOT NULL COMMENT '哈希链序号，单调递增',
    event_type    VARCHAR(64)  NOT NULL COMMENT '事件类型',
    operator_id   BIGINT       NULL COMMENT '操作人用户 ID（系统事件为空）',
    exec_identity VARCHAR(128) NULL COMMENT '实际执行身份（数据库账号）',
    datasource_id BIGINT       NULL COMMENT '目标数据源',
    action        VARCHAR(64)  NOT NULL COMMENT '动作',
    object_id     VARCHAR(128) NULL COMMENT '对象标识（表、工单等）',
    payload_json  MEDIUMTEXT   NULL
                  COMMENT '规范化 JSON 原文（保留字节序，供哈希校验；不用 JSON 类型以免键序被重排）',
    prev_hash     CHAR(64)     NOT NULL COMMENT '前一条 hash；创世时为空串',
    hash          CHAR(64)     NOT NULL COMMENT 'SHA-256(prev_seq || prev_hash || event_type || operator_id || payload)',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_audit_event_seq (seq),
    KEY idx_audit_event_type_created (event_type, created_at),
    KEY idx_audit_event_operator (operator_id),
    KEY idx_audit_event_datasource (datasource_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '审计事件（append-only，哈希链）';
