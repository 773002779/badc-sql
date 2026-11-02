-- 平台元数据库：通知（V4，切片 3-A）
--
-- 为什么要这两张表：
--   * FR-13 要求工单/授权申请在关键节点自动通知到人。通知在业务事务提交后才异步投递，
--     投递结果必须可查、可重试、可解释失败，所以每个"收件人 × 渠道"的投递各落一行
--     notification；失败时把原因写进 last_error，而不是只留一行应用日志。
--   * 一个收件人投递失败不能拖垮其他收件人，更不能回滚已提交的业务事务。按行记录让失败
--     范围收敛到"这个人 × 这个渠道"，重试次数按行累加（attempts），运维可以只看 FAILED 行。
--   * notification_switch 让运维按事件、按系统临时关掉通知（例如系统维护窗口），不必改配置
--     重启；system_id 为空的行是全局默认，system_id 非空的行只覆盖该系统。
--
-- 约定沿用 V1–V3：
--   * 时间列统一以 UTC 存储，类型 datetime(3)，不做时区转换。
--   * 字符集统一 utf8mb4 / utf8mb4_0900_ai_ci。
--   * 表间关系用索引保障查询，不建外键：工单/系统有归档与删除路径，外键会阻塞它们。
--   * 枚举列加 CHECK，在数据库层挡住拼写错误。CHECK 自 MySQL 8.0.16 起才真正生效，
--     更低版本的服务器会**静默忽略**它 —— 平台元数据库最低要求 MySQL 8.0.16。
--   * 通知标题/正文只由白名单事实字段渲染，**不含 SQL 正文**（FR-13 硬约束）。本表存的是
--     渲染后的快照，改模板不会改写历史通知。
--
-- notification_switch 的唯一键 (event_type, system_id) 有个 MySQL 特性要记住：唯一索引
-- 允许多个 NULL，故 system_id IS NULL 的全局行在数据库层并不唯一，至多一条由服务层保证
-- （开关用 upsert 写入，不做物理删除）。

CREATE TABLE notification (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '通知 ID',
    event_type       VARCHAR(48)   NOT NULL COMMENT '事件类型（与 NotificationEventType 对齐，如 TICKET_SUBMITTED）',
    system_id        BIGINT        NULL COMMENT '相关业务系统 ID（授权类事件可能为空）',
    ticket_id        BIGINT        NULL COMMENT '相关工单 ID（非工单事件为空）',
    grant_request_id BIGINT        NULL COMMENT '相关授权申请 ID（非授权事件为空）',
    recipient_id     BIGINT        NOT NULL COMMENT '收件人用户 ID',
    channel          VARCHAR(16)   NOT NULL COMMENT '投递渠道：IN_APP|WECOM|EMAIL',
    status           VARCHAR(16)   NOT NULL
                     COMMENT '投递状态：PENDING|SENT|FAILED|SKIPPED（渠道未配置记为 SKIPPED，不算失败）',
    title            VARCHAR(200)  NOT NULL COMMENT '标题（渲染结果快照，不含 SQL 正文）',
    body             VARCHAR(2000) NOT NULL COMMENT '正文（渲染结果快照，不含 SQL 正文）',
    attempts         INT           NOT NULL DEFAULT 0 COMMENT '已尝试投递次数（重试时累加，初始 0）',
    last_error       VARCHAR(500)  NULL COMMENT '最后一次失败原因（成功或未投递时为空）',
    created_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    sent_at          DATETIME(3)   NULL COMMENT '投递成功时间（UTC，未成功时为空）',
    PRIMARY KEY (id),
    KEY idx_notification_recipient_created (recipient_id, created_at),
    KEY idx_notification_ticket (ticket_id),
    KEY idx_notification_status_created (status, created_at),
    CONSTRAINT ck_notification_channel
        CHECK (channel IN ('IN_APP', 'WECOM', 'EMAIL')),
    CONSTRAINT ck_notification_status
        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '通知投递记录（每个收件人 × 渠道一行，失败可查可重试）';

CREATE TABLE notification_switch (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '开关 ID',
    event_type VARCHAR(48) NOT NULL COMMENT '事件类型（与 notification.event_type 同域）',
    system_id  BIGINT      NULL COMMENT 'NULL 表示全局默认；非空表示只覆盖该系统',
    enabled    TINYINT(1)  NOT NULL COMMENT '该事件的通知是否开启',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
               ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_switch (event_type, system_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '通知事件开关（全局默认 + 按系统覆盖）';

-- 通知的收件人联系方式（R1 裁决）：直接挂在 sys_user 上，不另建联系方式表。
--
-- 两列都可空：多数用户只收站内通知，没有邮箱 / 企微账号是正常状态。缺联系方式的用户
-- **必须仍能被 NotificationTargetStore 查出来**（字段为 null），由各渠道 sender 自行判空跳过；
-- 若在这一层按 email is not null 过滤，"没填邮箱"就会变成"连站内消息也收不到"。
--
-- VARCHAR(190)：utf8mb4 下 190 * 4 = 760 字节，仍在 InnoDB 单列索引 767 字节限制内。
-- uk_sys_user_wecom 是唯一键：企微 userid 一人一号；MySQL 唯一索引允许多个 NULL，
-- 所以"大量用户没填企微"不会互相冲突。
ALTER TABLE sys_user
    ADD COLUMN email        VARCHAR(190) NULL COMMENT '通知收件邮箱',
    ADD COLUMN wecom_userid VARCHAR(190) NULL COMMENT '企微 userid';

ALTER TABLE sys_user
    ADD UNIQUE KEY uk_sys_user_wecom (wecom_userid);
