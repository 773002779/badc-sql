# 切片 3-A：通知（FR-13 子集）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让工单与授权申请在关键节点自动通知到人（站内 + 企微 + 邮件），且通知失败绝不影响业务流转。

**Architecture:** 业务服务只发布 `NotificationCommand`；`@TransactionalEventListener(AFTER_COMMIT)` 在业务事务提交后异步投递。投递器按"事件开关 → 接收人 → 渠道"逐层收敛，每个（接收人 × 渠道）落一行 `notification` 记录，重试 3 次后置 `FAILED`。渠道实现藏在 `ChannelSender` 接口后，未配置的渠道记 `SKIPPED` 而不是失败。

**Tech Stack:** Java 17（release）/ JDK 21、Spring Boot 3.2.12、Spring JDBC、`spring-boot-starter-mail`（新增）、`java.net.http.HttpClient`（企微）、JUnit 5 + AssertJ + MockMvc、MySQL 8.0.16+。

**Spec:** `docs/superpowers/specs/2026-10-20-sql-execution-platform-design.md`（FR-13 第 621–628 行；接收人口径对齐 §4.4 与 `TicketReviewService`）

## Global Constraints

- 迁移文件按版本追加：本计划用 `V4__notification.sql`，**不修改** V1–V3。
- 时间列一律 `DATETIME(3)` 存 UTC，不做时区转换；字符集 `utf8mb4` / `utf8mb4_0900_ai_ci`。
- **不建外键**（数据源/模块有下线场景）。
- **通知内容不含 SQL 正文**，这是硬约束，不是建议（FR-13）。
- 通知**绝不**阻塞或回滚业务事务：业务事务已提交后才投递，投递异常只记日志与 `FAILED` 记录。
- 所有通知相关的配置变更、开关变更**留审计**。
- 新增类遵循既有风格：构造器注入、无 Lombok、中文 javadoc 说明"为什么"。
- 测试命名：单元 `*Test`（surefire），集成 `*IT`（failsafe）；集成测试继承 `AbstractMySqlIT`。

## Review Focus

按"最可能咬到人"的顺序：

1. **通知正文泄露 SQL**：研发在 SQL 里写了身份证号，通知里出现原文。渲染器只允许取元数据字段；必须有测试喂一段含字面量的 SQL。
2. **通知把业务写坏**：`sender` 抛异常导致工单提交回滚。必须有测试让渠道抛异常并断言工单仍 201。
3. **接收人为空时崩溃**：系统没有任何运维归属，投递应记 `SKIPPED` 而不是 NPE/500。
4. **关闭开关后仍发送**：开关判定漏掉"按系统覆盖"这一层，导致该关的没关。
5. **重试把上游打挂**：3 次重试无退避，或重试发生在业务线程里。

---

### Task 1: `notification` 与 `notification_switch` 两张表

**Files:**
- Create: `platform-control/src/main/resources/db/migration/V4__notification.sql`
- Test: `platform-control/src/test/java/com/badc/sql/control/MigrationIT.java`（已存在，追加断言）

**Interfaces:**
- Produces: 表 `notification`、`notification_switch`，供后续所有任务读写。

- [ ] **Step 1: 写迁移**

`notification` 列：`id BIGINT PK AUTO_INCREMENT`、`event_type VARCHAR(48) NOT NULL`、`system_id BIGINT NULL`、`ticket_id BIGINT NULL`、`grant_request_id BIGINT NULL`、`recipient_id BIGINT NOT NULL`、`channel VARCHAR(16) NOT NULL`、`status VARCHAR(16) NOT NULL`、`title VARCHAR(200) NOT NULL`、`body VARCHAR(2000) NOT NULL`、`attempts INT NOT NULL DEFAULT 0`、`last_error VARCHAR(500) NULL`、`created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)`、`sent_at DATETIME(3) NULL`。索引：`(recipient_id, created_at)`、`(ticket_id)`、`(status, created_at)`。CHECK：`channel IN ('IN_APP','WECOM','EMAIL')`、`status IN ('PENDING','SENT','FAILED','SKIPPED')`。

`notification_switch` 列：`id BIGINT PK`、`event_type VARCHAR(48) NOT NULL`、`system_id BIGINT NULL COMMENT 'NULL 表示全局'`、`enabled TINYINT(1) NOT NULL`、`updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)`。唯一键 `uk_notification_switch (event_type, system_id)`。注：MySQL 唯一索引允许多个 NULL，故全局行（`system_id IS NULL`）需由服务层保证至多一条。

- [ ] **Step 2: 在 `MigrationIT` 追加断言**

断言 `notification`、`notification_switch` 存在且 `information_schema.columns` 里 `notification.status` 的 `column_type` 为 `varchar(16)`。

- [ ] **Step 3: 跑测试**

Run: `.\mvnw.cmd -o -pl platform-control -am verify -Dit.test=MigrationIT -Dfailsafe.failIfNoSpecifiedTests=false -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 4: 提交**

```bash
git add platform-control/src/main/resources/db/migration/V4__notification.sql platform-control/src/test/java/com/badc/sql/control/MigrationIT.java
git commit -m "feat(notify): add notification and notification_switch tables"
```

---

### Task 2: 事件类型与消息体（不含 SQL）

**Files:**
- Create: `platform-control/src/main/java/com/badc/sql/control/notify/NotificationEventType.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/notify/NotificationCommand.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/notify/NotificationRenderer.java`
- Test: `platform-control/src/test/java/com/badc/sql/control/notify/NotificationRendererTest.java`

**Interfaces:**
- Produces:
  - `enum NotificationEventType { TICKET_SUBMITTED, TICKET_APPROVED, TICKET_REJECTED, TICKET_L3_PENDING_LEAD, TICKET_EXECUTED, TICKET_EXEC_FAILED, GRANT_REQUESTED, GRANT_APPROVED, GRANT_REJECTED }`，每个值带 `String label()`（中文名，用于标题）。
  - `record NotificationCommand(NotificationEventType type, long systemId, Long ticketId, Long grantRequestId, Map<String, String> facts)` —— `facts` 的键是**固定白名单**（见下），由构造处负责填。
  - `NotificationRenderer.render(NotificationCommand command)` 返回 `record Rendered(String title, String body)`。

`facts` 允许的键（**只允许这些**）：`system`、`module`、`datasource`、`statementType`、`riskLevel`、`estimatedRows`、`submitter`、`ticketNo`、`link`、`reason`。`NotificationCommand` 的紧凑构造器必须拒绝白名单之外的键（当场抛 `IllegalArgumentException`）——这是"不含 SQL 正文"的结构性保证，而不是靠调用方自觉。

- [ ] **Step 1: 写失败测试**

```java
// 实施时已按 R11 删除一条测试：原写的 a_statement_with_literals_never_reaches_the_message
// 从未喂入任何 SQL，doesNotContain("身份证") 对任何实现恒真。端到端断言移交 Task 7。

@Test
void a_fact_outside_the_whitelist_is_refused() {
    assertThatThrownBy(() -> new NotificationCommand(
            NotificationEventType.TICKET_SUBMITTED, 1L, 42L, null, Map.of("sql", "update t set a=1")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sql");
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd -o -pl platform-control -am test -Dtest=NotificationRendererTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（类型不存在）——这是最干净的红。

- [ ] **Step 3: 实现**

`NotificationRenderer.render` 形如：`"【" + type.label() + "】" + facts.getOrDefault("system","")`，正文按"模块/数据源/语句类型/风险等级/影响行数预估/提交人/工单号/短链"逐项拼接，缺失项跳过。纯函数、无状态、无 Spring 依赖（与 `RiskEngine` 同风格）。

- [ ] **Step 4: 跑测试确认通过**

- [ ] **Step 5: 提交**

```bash
git commit -m "feat(notify): render notifications from a whitelisted fact map, never from SQL"
```

---

### Task 3: 接收人解析

**Files:**
- Create: `platform-control/src/main/java/com/badc/sql/control/notify/NotificationRecipients.java`
- Test: `platform-control/src/test/java/com/badc/sql/control/notify/NotificationRecipientsIT.java`

**Interfaces:**
- Consumes: `NotificationEventType`（Task 2）
- Produces: `List<Long> NotificationRecipients.resolve(long systemId, NotificationEventType type)`

规则（**必须与 `TicketReviewService` 的审批人口径一致**）：取 `sys_system_operator where system_id = ?` 的 `user_id`；`type == TICKET_L3_PENDING_LEAD` 时额外并入 `operator_type = 'LEAD'` 的行（其余事件不通知组长）。返回去重、按 id 升序、可能为空列表。**不做角色过滤**——归属是数据库里的事实（与 `TicketMapper.countLeads` 同源）。

- [ ] **Step 1: 写失败测试**（真实 MySQL，夹具 `seed/test-fixtures.sql`：系统 2 有 PRIMARY ops01）

断言 `resolve(2L, TICKET_SUBMITTED)` 不含 admin01 且含 2L；`resolve(999L, ...)` 为空列表（系统不存在）；`resolve(2L, TICKET_L3_PENDING_LEAD)` 在补一行 LEAD 归属后包含该用户。

- [ ] **Step 2: 跑测试确认失败**
- [ ] **Step 3: 实现**（一条 SQL + 一次可选的 LEAD 查询，或一条 `in` 查询）
- [ ] **Step 4: 跑测试确认通过**
- [ ] **Step 5: 提交**：`git commit -m "feat(notify): resolve recipients from system ownership"`

---

### Task 4: 渠道接口与三个实现

**Files:**
- Create: `platform-control/src/main/java/com/badc/sql/control/notify/channel/ChannelSender.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/notify/channel/InAppSender.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/notify/channel/WeComSender.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/notify/channel/SmtpSender.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/notify/NotificationTargetStore.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/notify/NotificationProperties.java`
- Modify: `platform-control/pom.xml`（加 `spring-boot-starter-mail` 依赖）
- Test: `platform-control/src/test/java/com/badc/sql/control/notify/channel/WeComSenderTest.java`、`SmtpSenderTest.java`

**Interfaces:**
- Produces:
  - `interface ChannelSender { NotificationChannel channel(); boolean isConfigured(); void send(NotificationTarget target, String title, String body) throws Exception; }`
  - `record NotificationTarget(long userId, String loginName, String email, String wecomUserId)`
  - `NotificationTargetStore.load(List<Long> userIds)` → `List<NotificationTarget>`（`sys_user` 左连接出 `email` / `wecom_userid`；联系方式缺失时保留该用户、字段为 `null`，由各 sender 自行判空跳过——**不要**在这里过滤掉用户，否则"没填邮箱"会变成"这个人收不到站内消息"）。
  - `enum NotificationChannel { IN_APP, WECOM, EMAIL }`（建在 `notify` 包，与 Task 1 的 CHECK 对齐）
  - `NotificationProperties`：`badcsql.notify.wecom.{enabled,corp-id,agent-id,secret}`、`badcsql.notify.mail.{enabled,from}`、`badcsql.notify.retry.{max-attempts:3,base-backoff-millis:500}`

- [ ] **Step 1: `sys_user` 加两列**——直接追加到 Task 1 的 `V4__notification.sql` 末尾（V4 尚未合并，不必再开一个新版本号）：`ALTER TABLE sys_user ADD COLUMN email VARCHAR(190) NULL COMMENT '通知收件邮箱', ADD COLUMN wecom_userid VARCHAR(190) NULL COMMENT '企微 userid';` 并加 `UNIQUE KEY uk_sys_user_wecom (wecom_userid)`。

同时在本任务新增 `NotificationTargetStore`（见 Interfaces 的 `load`），它的 SQL 是本任务唯一读 `sys_user` 的地方。

- [ ] **Step 2: 写失败测试**

`SmtpSenderTest`：用假的 `JavaMailSender`（Mockito）断言 `isConfigured()` 在 `enabled=false` 时为 false，且 `send` 调用 `JavaMailSender.send` 一次、收件人为 `target.email()`。
`WeComSenderTest`：用 `HttpServer`（JDK 内置）起一个桩，断言请求 JSON 含 `touser`、不含 SQL 关键词；`errcode != 0` 时抛异常。

- [ ] **Step 3: 跑测试确认失败**
- [ ] **Step 4: 实现**

`InAppSender.isConfigured()` 恒 `true`，`send` 空实现（站内记录的"投递"就是那行 `notification` 本身）。
`WeComSender`：`GET /cgi-bin/gettoken?corpid=&corpsecret=` 缓存 token（提前 2 分钟过期），`POST /cgi-bin/message/send`，`errcode != 0` 抛异常并带 `errmsg`。
`SmtpSender`：`SimpleMailMessage`，`from` 取配置。

- [ ] **Step 5: 跑测试确认通过**
- [ ] **Step 6: 提交**：`git commit -m "feat(notify): wecom, smtp and in-app channel senders"`

---

### Task 5: 投递器（重试、落库、失败记录）

**Files:**
- Create: `platform-control/src/main/java/com/badc/sql/control/notify/NotificationStore.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/notify/NotificationDispatcher.java`
- Test: `platform-control/src/test/java/com/badc/sql/control/notify/NotificationDispatcherTest.java`

**Interfaces:**
- Consumes: Task 2 的 `Rendered`，Task 3 的 `resolve`，Task 4 的 `ChannelSender`
**契约（R13，Task 3 落地时确定）**：`NotificationRecipients.resolve` 对 `type == null` 抛
`IllegalArgumentException`，不会静默落进基线分支。投递器**必须**保证构造 command 时 `type` 非空；
若从可空列构造事件，会在业务流里抛异常而不是返回空列表——这与"通知绝不拖垮业务"的要求冲突。
- Produces:
  - `NotificationStore.insert(long recipientId, NotificationEventType type, Long systemId, Long ticketId, Long grantRequestId, NotificationChannel channel, String title, String body)` → `long id`
  - `NotificationStore.markSent(long id, int attempts)`、`markFailed(long id, int attempts, String error)`、`markSkipped(long id, String reason)`
  - `NotificationStore.failureCount(long ticketId)` → `int`、`NotificationStore.listForTicket(long ticketId)` → `List<NotificationRow>`
  - `NotificationDispatcher.dispatch(NotificationCommand command)` → `void`

投递逻辑：对每个接收人、每个 `isConfigured()` 的渠道写一行 `PENDING`，然后尝试发送；成功 `SENT`，未配置 `SKIPPED`，异常按 `max-attempts` 重试（第 n 次退避 `base * 2^(n-1)`），仍失败 `FAILED` 且 `last_error` 记脱敏后的异常消息。**单个接收人失败不影响其他人**，整个 `dispatch` 不抛异常。

- [ ] **Step 1: 写失败测试**

```java
@Test
void a_channel_failure_does_not_stop_other_recipients_and_never_throws() {
    // 两个接收人、一个会抛异常的 sender
    assertThatCode(() -> dispatcher.dispatch(command)).doesNotThrowAnyException();
    assertThat(store.countByStatus(NotificationStatus.FAILED)).isEqualTo(1);
    assertThat(store.countByStatus(NotificationStatus.SENT)).isEqualTo(1);
}
```

- [ ] **Step 2: 跑测试确认失败**
- [ ] **Step 3: 实现**（退避用 `Thread.sleep`，仅在单测里可被替换；生产走异步线程，不阻塞业务）
- [ ] **Step 4: 跑测试确认通过**
- [ ] **Step 5: 提交**：`git commit -m "feat(notify): dispatcher with bounded retries and per-recipient failure isolation"`

---

### Task 6: 事件开关（全局 + 按系统覆盖）

**Files:**
- Create: `platform-control/src/main/java/com/badc/sql/control/notify/NotificationSwitchService.java`
- Test: `platform-control/src/test/java/com/badc/sql/control/notify/NotificationSwitchServiceIT.java`

**Interfaces:**
- Produces:
  - `boolean NotificationSwitchService.isEnabled(NotificationEventType type, long systemId)` —— 先查 `system_id = ?` 的行，没有则查 `system_id IS NULL` 的行，都没有则默认 `true`（默认开）。
  - `void setGlobal(NotificationEventType type, boolean enabled, long actorId)`、`void setForSystem(NotificationEventType type, long systemId, boolean enabled, long actorId)` —— 都写审计。

- [ ] **Step 1: 写失败测试**：系统级 `false` 覆盖全局 `true`；无任何行时返回 `true`；`setGlobal` 写一条审计。
- [ ] **Step 2–4:** 红 → 实现 → 绿（`NotificationSwitchMapper` 两条 SQL + 审计调用 `AuditService`）。
- [ ] **Step 5: 提交**：`git commit -m "feat(notify): per-event and per-system notification switches with audit"`

---

### Task 7: 接线到工单流程

**Files:**
- Create: `platform-control/src/main/java/com/badc/sql/control/notify/TicketNotificationListener.java`
- Modify: `platform-control/src/main/java/com/badc/sql/control/ticket/TicketService.java`（提交后发布事件）
- Modify: `platform-control/src/main/java/com/badc/sql/control/ticket/TicketReviewService.java`（通过/驳回后发布事件）
- Modify: `platform-control/src/main/java/com/badc/sql/control/ticket/exec/TicketExecutionService.java`（执行终态后发布事件）
- Test: `platform-control/src/test/java/com/badc/sql/control/notify/TicketNotificationIT.java`

**Interfaces:**
- Consumes: `NotificationDispatcher.dispatch`
- Produces: 业务服务发布 `TicketNotificationEvent(NotificationCommand command)`（一个 record 包住 command，因为 Spring 事件需要非 null 载荷）的 Spring 事件，经 `ApplicationEventPublisher.publishEvent`。
- `TicketNotificationListener` 用 `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async`。

映射：`TICKET_SUBMITTED`（提交，收件人=系统运维）；`REVIEWING_L2` 时追加 `TICKET_L3_PENDING_LEAD`；`TICKET_APPROVED` / `TICKET_REJECTED`（给提交人 + 一级审核人）；`TICKET_EXECUTED` / `TICKET_EXEC_FAILED`（给提交人 + 系统运维）。

**本任务必须补的端到端断言（R11 移交）**：构造一张语句里带字面量的工单（例如
`update t_order set note = '身份证110101199001011234' where id = 1`），断言落库的 `notification.title`
与 `body` **都不含**该字面量。这是 FR-13「不含 SQL 正文」在测试层唯一的承重断言——Task 2 原写的同名
测试因为「从未喂入 SQL」而恒真，已按 R11 删除，真正的证明点在这里。只断言「白名单外的键被拒」不够：
那只证明键名受限，不证明平台不会把工单内容塞进白名单字段。
- [ ] **Step 1: 写失败测试（关键：通知失败不影响业务）**

```java
@Test
void a_broken_channel_never_fails_the_submission() {
    // 让 WECOM sender 抛异常（桩 bean 覆盖）
    ResponseEntity<String> response = postTicket(DEV_TOKEN, body("update t_order set a=1 where id=1", null, "update t_order set a=0 where id=1"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(countTickets()).isEqualTo(1);
}
```

- [ ] **Step 2: 跑测试确认失败**
- [ ] **Step 3: 实现**（含 `@EnableAsync` + 一个有界 `TaskExecutor`；禁止用默认 `SimpleAsyncTaskExecutor` 无界建线程）
- [ ] **Step 4: 跑测试确认通过**
- [ ] **Step 5: 提交**：`git commit -m "feat(notify): publish ticket notifications after commit, isolated from the business path"`

---

### Task 8: 查询端点 + 工单详情暴露失败

**Files:**
- Create: `platform-control/src/main/java/com/badc/sql/control/notify/NotificationController.java`
- Modify: `platform-control/src/main/java/com/badc/sql/control/ticket/TicketQueryService.java`（`TicketDetail` 追加 `notificationFailures`）
- Test: `platform-control/src/test/java/com/badc/sql/control/notify/NotificationControllerIT.java`

**Interfaces:**
- Produces: `GET /api/v1/notifications`（只返回本人 `recipient_id` 的记录，按 `created_at desc`，`page`/`size` 同 `TicketQueryService` 口径）；`GET /api/v1/tickets/{id}/notifications`（详情权限同 `TicketQueryService.detail`）。

- [ ] **Step 1: 写失败测试**：未登录 401；研发只看自己的（造一条别人的记录，断言不出现）；无权访问的工单 → 403。
- [ ] **Step 2–4:** 红 → 实现 → 绿。
- [ ] **Step 5: 提交**：`git commit -m "feat(notify): notification list endpoints and surface delivery failures in ticket detail"`

---

## 交接

8 个任务完成后：通知可从工单流程自动发出、可关闭、可查、失败可见，且不影响业务。**授权申请（Part B）在此之后做**，它的"通知运维"步骤直接复用 Task 5 的 `dispatch`。

## 本计划**不**覆盖的 FR-13 事件（明确留给后续）

FR-13 列了 10 类事件，本计划只做与**已交付**流程相关的 7 类。以下 3 类对应的业务功能还不存在，做通知没有意义，等各自的功能切片落地时随该切片补：

| 事件 | 缺的前置功能 |
|---|---|
| 导出申请 / 导出审批 | FR-04 的 >1000 行导出审批流程（切片 3 之后） |
| 快照清理预警 | FR-02 的 180 天保留期清理任务 |
| 磁盘空间告警 | FR-14 的磁盘水位阈值与巡检 |

另外 FR-13 的"按系统覆盖"本期只做了开关与 SQL 层，**没有管理端点**（改开关只能走数据库）——因为前端未做，加一个只有运维会用、又没有页面的端点属于 YAGNI；`NotificationSwitchService` 的 `setGlobal` / `setForSystem` 已备好，等页面或 CLI 需要时再包一层控制器。
