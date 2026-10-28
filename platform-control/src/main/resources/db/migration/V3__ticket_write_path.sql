-- 平台元数据库：写路径（V3，切片 2）
--
-- 本迁移建工单写路径的 7 张表。约定沿用 V1：
--   * 时间列统一以 UTC 存储，类型 datetime(3)，不做时区转换。
--   * 字符集统一 utf8mb4 / utf8mb4_0900_ai_ci。
--   * 表间关系用索引保障查询，不建外键：工单要跨系统/数据源做历史查询，外键会阻塞后续的删除路径。
--   * JSON 类列（risk_snapshot_json / table_fingerprints_json / detail_json）一律用
--     MEDIUMTEXT/TEXT 保存规范化原文，**不用 MySQL 的 JSON 类型**：JSON 类型会重排键序，
--     而 risk_snapshot_json 与 table_fingerprints_json 的字节序要参与哈希比对
--     （同 V1 audit_event.payload_json 的理由）。
--   * 枚举列加 CHECK 约束，在数据库层挡住拼写错误。CHECK 自 MySQL 8.0.16 起才真正生效，
--     更低版本的服务器会**静默忽略**它 —— 平台元数据库最低要求 MySQL 8.0.16。

CREATE TABLE ticket (
    id                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '工单 ID',
    system_id             BIGINT       NOT NULL COMMENT '归属业务系统 ID',
    module_id             BIGINT       NOT NULL COMMENT '发起模块 ID（研发经由哪个模块看到该数据源）',
    datasource_id         BIGINT       NOT NULL COMMENT '目标数据源 ID',
    ticket_type           VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' COMMENT '工单类型：NORMAL|ROLLBACK',
    status                VARCHAR(32)  NOT NULL DEFAULT 'DRAFT'
                          COMMENT '状态：DRAFT|REVIEWING_L1|REVIEWING_L2|APPROVED|EXECUTING|SUCCEEDED|PARTIAL_FAILED|FAILED|NEEDS_MANUAL_CONFIRM|REJECTED|EXPIRED|CANCELLED|ROLLED_BACK',
    risk_level            VARCHAR(8)   NOT NULL COMMENT '整单风险等级：L0|L1|L2|L3（取全部语句中最严的一条）',
    risk_snapshot_json    MEDIUMTEXT   NULL
                          COMMENT '风险快照规范化 JSON 原文（参与哈希；不用 JSON 类型以免键序被重排）',
    risk_snapshot_hash    CHAR(64)     NOT NULL COMMENT '风险快照 SHA-256（十六进制小写）',
    title                 VARCHAR(200) NOT NULL COMMENT '工单标题',
    change_reason         TEXT         NULL COMMENT '变更原因',
    rollback_plan         TEXT         NULL COMMENT '回滚方案（DDL 必填，文本描述）',
    rollback_sql          MEDIUMTEXT   NULL COMMENT '回滚 SQL 原文（DML 必填，可含多条语句）',
    statement_fingerprint CHAR(64)     NOT NULL COMMENT '全部语句按序拼接后的 SHA-256，执行前校验语句未被篡改',
    snapshot_enabled      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '提交时是否抓取结构快照（本切片恒为开启，不提供关闭入口）',
    expected_execute_at   DATETIME(3)  NULL COMMENT '期望执行时间（UTC）',
    submitter_id          BIGINT       NOT NULL COMMENT '提交人用户 ID',
    origin_ticket_id      BIGINT       NULL COMMENT '回滚单对应的原工单 ID（NORMAL 单为空）',
    rollback_reason       TEXT         NULL COMMENT '回滚原因（ROLLBACK 单填写）',
    created_at            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                          ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    approved_at           DATETIME(3)  NULL COMMENT '审批通过时间（UTC）',
    executed_at           DATETIME(3)  NULL COMMENT '开始执行时间（UTC）',
    finished_at           DATETIME(3)  NULL COMMENT '进入终态时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_ticket_status_created (status, created_at),
    KEY idx_ticket_submitter (submitter_id),
    KEY idx_ticket_datasource (datasource_id),
    CONSTRAINT ck_ticket_ticket_type
        CHECK (ticket_type IN ('NORMAL', 'ROLLBACK')),
    CONSTRAINT ck_ticket_status
        CHECK (status IN ('DRAFT', 'REVIEWING_L1', 'REVIEWING_L2', 'APPROVED', 'EXECUTING',
                          'SUCCEEDED', 'PARTIAL_FAILED', 'FAILED', 'NEEDS_MANUAL_CONFIRM',
                          'REJECTED', 'EXPIRED', 'CANCELLED', 'ROLLED_BACK')),
    CONSTRAINT ck_ticket_risk_level
        CHECK (risk_level IN ('L0', 'L1', 'L2', 'L3'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '变更工单';

CREATE TABLE ticket_statement (
    id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '语句 ID',
    ticket_id      BIGINT      NOT NULL COMMENT '归属工单',
    seq_no         INT         NOT NULL COMMENT '工单内序号，从 1 开始（即执行顺序）',
    sql_text       MEDIUMTEXT  NOT NULL COMMENT '语句原文（执行前用工单指纹校验未被篡改）',
    statement_type VARCHAR(32) NOT NULL COMMENT '语句类型（分类结果，如 SELECT|INSERT|UPDATE|DELETE|ALTER|CREATE|DROP|TRUNCATE）',
    risk_level     VARCHAR(8)  NOT NULL COMMENT '单条语句风险等级：L0|L1|L2|L3',
    status         VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                   COMMENT '执行状态：PENDING|RUNNING|SUCCEEDED|FAILED|SKIPPED',
    affected_rows  BIGINT      NULL COMMENT '影响行数（失败或未执行时为空）',
    error_message  TEXT        NULL COMMENT '失败原因（成功时为空）',
    elapsed_ms     BIGINT      NULL COMMENT '执行耗时毫秒（未执行时为空）',
    executed_at    DATETIME(3) NULL COMMENT '执行时间（UTC，未执行时为空）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ticket_statement (ticket_id, seq_no),
    CONSTRAINT ck_ticket_statement_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '工单语句（一单一语句串行执行）';

CREATE TABLE ticket_review (
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '审核 ID',
    ticket_id    BIGINT      NOT NULL COMMENT '归属工单',
    review_level TINYINT     NOT NULL COMMENT '审核级别：1=运维一级（L1/L2 终审或 L3 一级），2=组长终审（仅 L3）',
    reviewer_id  BIGINT      NOT NULL COMMENT '审核人用户 ID（提交人不可审核自己的工单）',
    decision     VARCHAR(16) NOT NULL COMMENT '审核结论：APPROVED|REJECTED',
    comment      TEXT        NULL COMMENT '审核意见',
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '审核时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ticket_review (ticket_id, review_level),
    CONSTRAINT ck_ticket_review_decision
        CHECK (decision IN ('APPROVED', 'REJECTED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '工单审核记录（每个审核级别至多一条）';

CREATE TABLE ticket_event (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '事件 ID',
    ticket_id   BIGINT      NOT NULL COMMENT '归属工单',
    event_type  VARCHAR(64) NOT NULL COMMENT '事件类型（SUBMITTED|APPROVED_L1|APPROVED_L2|REJECTED|EXECUTED|EXPIRED|ROLLED_BACK|...）',
    actor_id    BIGINT      NULL COMMENT '操作人用户 ID（系统事件为空）',
    detail_json TEXT        NULL COMMENT '事件明细 JSON 原文（不用 JSON 类型以免键序被重排）',
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事件时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_ticket_event_ticket (ticket_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '工单事件流水';

CREATE TABLE schema_snapshot (
    id                      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '快照 ID',
    ticket_id               BIGINT       NOT NULL COMMENT '归属工单',
    change_seq              INT          NOT NULL COMMENT '变更序号：0=提交时的结构指纹，1=执行前的结构快照',
    datasource_id           BIGINT       NOT NULL COMMENT '目标数据源 ID',
    snapshot_ref            VARCHAR(512) NOT NULL COMMENT '快照落盘相对路径：{systemId}/{datasourceId}/{ticketId}/{changeSeq}',
    table_fingerprints_json TEXT         NULL
                            COMMENT '逐表结构指纹 JSON 原文（表不存在时为空串，表示前置状态为空；不用 JSON 类型以免键序被重排）',
    captured_at             DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '抓取时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_schema_snapshot_ticket (ticket_id, change_seq)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '结构快照（保留 180 天）';

CREATE TABLE risk_acknowledgement (
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '确认 ID',
    ticket_id          BIGINT       NOT NULL COMMENT '归属工单',
    stage              VARCHAR(16)  NOT NULL COMMENT '确认阶段：SUBMIT|REVIEW|L3_REVIEW|EXECUTE',
    operator_id        BIGINT       NOT NULL COMMENT '确认人用户 ID',
    risk_snapshot_hash CHAR(64)     NOT NULL COMMENT '确认时的风险快照 SHA-256（执行前须与工单当前快照一致）',
    ip                 VARCHAR(64)  NULL COMMENT '来源 IP',
    user_agent         VARCHAR(512) NULL COMMENT 'User-Agent 原文',
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '确认时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_risk_acknowledgement_ticket (ticket_id, stage, created_at),
    CONSTRAINT ck_risk_acknowledgement_stage
        CHECK (stage IN ('SUBMIT', 'REVIEW', 'L3_REVIEW', 'EXECUTE'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '风险知悉确认（按阶段留痕）';

CREATE TABLE datasource_exec_lock (
    datasource_id BIGINT      NOT NULL COMMENT '数据源 ID（主键本身就是互斥体：同一数据源同时只能有一把锁）',
    ticket_id     BIGINT      NOT NULL COMMENT '持有锁的工单 ID',
    acquired_at   DATETIME(3) NOT NULL COMMENT '获取时间（UTC）',
    PRIMARY KEY (datasource_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '数据源执行互斥锁（单数据源写并发 = 1）';
