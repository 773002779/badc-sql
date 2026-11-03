-- 平台元数据库：授权申请（V5，切片 3-B）
--
-- 为什么要这张表：
--   FR-02/§4.4② 要求研发自助申请"模块授权（查询 + 提单，可选导出）"，由该系统的运维审批后生效。
--   申请本身必须是一个可追踪的实体：谁、什么时候、为哪个模块、要什么、为什么要，以及谁批的。
--   若只把"待审批"做成一条通知，审批完就什么都不剩，事后无法回答"这条授权是谁批的"。
--
-- 授权粒度沿用 V1（**不新增列**）：
--   user_module_grant 存在 = 可查询 + 可提单；can_export 单独控制导出。
--   切片 1/2 已把"有授权 ⇒ 可见 ⇒ 可查/可提单"写进 VisibilityService 与 TicketAssignmentService，
--   再加 can_query/can_submit 会改变已交付语义，还要回填历史数据。所以审批通过时只写 user_module_grant。
--
-- 为什么不为"同一人同一模块只能有一条 PENDING"建唯一索引：
--   MySQL 的唯一索引无法表达"仅对 PENDING 去重"这个条件约束（部分索引不存在）。若对
--   (user_id, module_id) 建唯一键，则历史已处理记录会让下一次申请直接撞键——研发被驳回过一次就再也申请不了。
--   改由服务层在事务内"先查后插"，并在集成测试里覆盖重复提交。
--
-- 约定沿用 V1–V4：
--   * 时间列统一以 UTC 存储，类型 datetime(3)，不做时区转换。
--   * 字符集统一 utf8mb4 / utf8mb4_0900_ai_ci。
--   * 表间关系用索引保障查询，不建外键：模块有删除路径（ModuleMapper.deleteGrants），外键会阻塞它。
--   * 枚举列加 CHECK。CHECK 自 MySQL 8.0.16 起才真正生效，更低版本的服务器会**静默忽略**它 ——
--     平台元数据库最低要求 MySQL 8.0.16（与 V1–V4 同一条约束）。
--
-- 状态机：PENDING → APPROVED | REJECTED | CANCELLED，终态不可再变。
--   所有迁移都带 where status = 'PENDING'（乐观并发），影响行数 0 即"已被别人处理过"，服务层报 409。

CREATE TABLE grant_request (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '申请 ID',
    user_id        BIGINT       NOT NULL COMMENT '申请人用户 ID',
    module_id      BIGINT       NOT NULL COMMENT '申请的模块 ID',
    can_export     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '本次申请是否包含导出权',
    reason         VARCHAR(500) NOT NULL COMMENT '申请理由（必填，便于审批人判断）',
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                   COMMENT '状态：PENDING|APPROVED|REJECTED|CANCELLED',
    reviewer_id    BIGINT       NULL COMMENT '审批人用户 ID（未处理时为空）',
    review_comment VARCHAR(500) NULL COMMENT '审批意见 / 驳回理由',
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '申请时间（UTC）',
    reviewed_at    DATETIME(3)  NULL COMMENT '审批时间（UTC，未处理时为空）',
    PRIMARY KEY (id),
    -- "我的申请"：按人倒序分页。
    KEY idx_grant_request_user_status (user_id, status, created_at),
    -- "待我审批"：按模块找 PENDING，再经 sys_module.system_id 收敛到本人归属的系统。
    KEY idx_grant_request_module_status (module_id, status),
    KEY idx_grant_request_status_created (status, created_at),
    CONSTRAINT ck_grant_request_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '模块授权申请（研发自助申请，系统运维审批）';
