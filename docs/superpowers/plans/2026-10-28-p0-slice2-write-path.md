# SQL 执行平台 — 切片 2：写路径主干 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让一张研发变更工单能走完"提交 → 分级 → 审批 → 风险确认 → 结构快照 → 执行 → 回滚"的完整写路径，且每一步都有服务端强制与审计留痕。

**Architecture:** 控制面负责状态机、风险分级、审批与编排；执行器负责连库、抓结构快照、按账号类型执行并回传逐条结果。执行器新增内部接口 `/internal/v1/{snapshot,fingerprint,explain,execute}`，全部要求内部令牌（本切片任务 1 补上，切片 1 遗留的无认证通道在此关闭）。工单执行是**异步**的：`POST /execute` 返回 202，前端轮询详情，因为 DDL 超时 3600s 不可能挂在一个 HTTP 请求里。

**Tech Stack:** Java 17 · Spring Boot 3.2.12 · Spring Security（JWT + 内部令牌）· JdbcTemplate · Flyway · MySQL 8（平台库）· MySQL 5.7/8.0（目标库）· Maven Wrapper 3.9.6 · JUnit 5 · AssertJ

**Spec:** `docs/superpowers/specs/2026-10-20-sql-execution-platform-design.md`

## Global Constraints

- 仅纳管生产库；不引入 `env` 维度。
- 研发**不可见**连接信息：数据源接口只返回 `{id, name, dbName, systemId, type}`，不含 host/port/user/密码/连接串。
- 结构性破坏 DDL 恒为 L3，**代码内置**，不受任何阈值配置影响，不提供下调入口（管理员也没有）：`drop table`、`truncate`、`rename table`、`alter drop column`、`alter drop index`、`alter modify` 类型收窄、改字符集、`drop partition`、`drop database`/`drop schema`（后者直接 BLOCK）。
- `drop database` / `drop schema` / 一条语句里多个 `;` → **提交阶段直接 BLOCK**，进不到审批。
- 审批深度：研发工单 L1/L2 → 运维一级；L3 → 运维一级 + 组长终审（两级**不可同一人**）。提交人 ≠ 审核人。
- 运维 L1/L2 临时执行自确认即执行；L3 走组长一级审批。**本切片不实现运维临时执行通道**（见「明确不做」）。
- 管理员**不可执行**生产语句（只能配置与授权）。
- 结构快照默认开启、提交时**不可关闭**（本切片不提供关闭开关；`snapshotEnabled=false` 的 L3 升档判定已在引擎里实现，留给切片 3 的关闭理由）。
- 结构快照抓取失败 → **禁止执行**；表不存在（新建表）→ 记录"前置状态为空"，不算失败。
- 单数据源写并发 = 1；同一时刻只有一个工单在该数据源执行。
- 语句超时：查询 60s / 写 300s / DDL 3600s（应用层）。
- 单工单语句上限 5000 条；一单内语句串行执行。
- 已通过工单不可改语句；需要改则新建工单，旧单作废。
- 审计 **append-only** 哈希链（`prev_hash` + `hash`），保留 2 年，禁止 UPDATE/DELETE。
- 结构快照保留 180 天，落 `<root>/{systemId}/{datasourceId}/{ticketId}/{changeSeq}/schema.json|schema.sql`。
- 平台元数据库：MySQL **8.0.16+**（低于此版本 `CHECK` 约束被静默忽略）。时间以 **UTC** 存储，展示按 `Asia/Shanghai`。
- 连接凭证 AES-256-GCM 加密，密钥来自 `BADCSQL_CRED_KEY`（32 字节 base64），**任何接口不回显明文**。
- 支持 MySQL **5.7 与 8.0**，版本差异运行时探测，不写死。
- 执行器内部接口**必须**带内部令牌；令牌未配置时执行器拒绝全部内部请求（fail-closed）。
- 所有 Java 包名前缀 `com.badc.sql`。`maven.compiler.release=17`。
- 构建一律用 `.\mvnw.cmd`（系统 Maven 3.6.1 低于 Wrapper 要求）。单测走 surefire（`*Test`），集成测试走 failsafe `verify`（`*IT`）。
- 集成测试连本地 MySQL `127.0.0.1:3306`（`root`/`root`），每类一个随机 `badc_test_*` schema，不用 Testcontainers。

## Review Focus

以下五类输入本切片必然遇到，但没有一条会自然落进某个任务的"顺手写的测试"。每条都在归属任务里写成了显式用例。

1. **结构性关键字藏在注释、字符串或反引号里** —— `update t set c = 'drop table x'`、`-- drop table x`、``alter table `drop table` add column a`` 都不是 DDL。只按关键字扫描会把只读/普通 DML 误判成 L3，或反过来漏判。→ 任务 3。
2. **`alter table` 的多子句、`if exists`、反引号限定名** —— `alter table t drop column a, add column b` 里最严重的子句在后面，只看第一个动作就降成 L1；`drop table if exists t` 不能因为 `if exists` 就不算 `drop table`。→ 任务 3、任务 4。
3. **审批之后语句或表结构被改动** —— 研发改了工单语句再点执行、或审核通过后别人给表加了列。两者都必须中止，且错误要说清是"语句变了"还是"表 `t_order` 结构漂移（新增列 `remark`）"。→ 任务 7、任务 11。
4. **同一数据源两个工单同时点执行** —— 必须只有一个进入 `EXECUTING`，另一个得到明确的 409；失败方不得残留快照、不得把语句置成 `RUNNING`、不得占着锁不放。→ 任务 11。
5. **快照落盘路径与"表不存在"** —— 表名可含反引号与点号，`ticketId` 来自请求；相对路径里出现 `..` 会把快照写到 PVC 之外。而 `drop table t` 时 t 已经不存在，既不能当成"漂移通过"，也不能让执行失败。→ 任务 5、任务 11。

---

## 明确不做（本切片边界，均为决定而非待定）

| 不做 | 原因 | 归属 |
|---|---|---|
| 运维临时执行通道（`DirectExecRecord`、L3 自动生成工单） | 与工单走不同的权限模型与状态机，混在一个切片里两套都不稳 | 切片 3 |
| 通知（企微 / 邮件） | 通知失败是否阻塞工单流转是独立的状态机问题 | 切片 3 |
| 导出（>1000 行审批、100 万行/500MB） | 读路径增强，与写路径无耦合 | 切片 3 |
| 风险阈值 DB 化 + YAML DSL + 三级作用域 + 热更新（§10.3-6） | 本切片阈值来自 `application.yml`（`badcsql.risk.*`），重启生效；内置 9 条硬规则本就不许配置 | 切片 3 |
| 执行前**精确**计数（§7.2-6）与"跨档中止回退审批" | 需要 SQL 改写（`update T set .. where W` → `select count(*) from T where W`），无现成解析器。本切片用 `EXPLAIN` 估算，见任务 6 的取舍 | 切片 3 |
| 逐系统执行窗口维护（§4.6） | 本切片只实现全局默认窗口 20:00–06:00（`badcsql.risk.window.*`）与 +1 级 | 切片 3 |
| 执行队列（"后来者排队"§11） | 本切片后来者得到 409，由用户重试；真正的排队需要 worker 与优先级 | 切片 3 |
| online DDL / gh-ost（P1） | 不在 P0 | P1 |
| 前端页面 | 切片 1 已定：UI 独立切片 | 切片 5 |

---

## File Structure

```
platform-common/src/main/java/com/badc/sql/common/
├── dto/SnapshotResult.java          # 新增：快照引用 + 表指纹
├── dto/ExplainEstimate.java         # 新增：affected rows 估算
├── dto/ExecuteResult.java           # 新增：单条语句执行结果
├── dto/ExecAccountType.java         # 新增：DML | DDL
└── sql/SqlText.java                 # 修改：加 splitStatements（顶层分号切分）

platform-executor/src/main/java/com/badc/sql/executor/
├── internal/InternalAuthFilter.java # 新增：内部令牌校验（fail-closed）
├── internal/InternalToken.java      # 新增：常量时间比较
├── schema/SchemaSnapshotter.java    # 新增：SHOW CREATE TABLE 等元数据 → 文件 + 指纹
├── schema/SnapshotPaths.java        # 新增：相对路径校验与拼接
├── exec/WriteRunner.java            # 新增：单条写语句，按账号类型与超时
├── exec/ExplainRunner.java          # 新增：EXPLAIN 估算
└── api/ExecutorController.java      # 修改：加 snapshot / fingerprint / explain / execute

platform-control/src/main/java/com/badc/sql/control/
├── ticket/TicketStatus.java         # 新增：状态枚举
├── ticket/TicketStateMachine.java   # 新增：唯一允许的状态迁移表
├── ticket/TicketEntity.java         # 新增
├── ticket/TicketMapper.java         # 新增
├── ticket/TicketStatementMapper.java# 新增
├── ticket/TicketReviewMapper.java   # 新增
├── ticket/TicketEventMapper.java    # 新增
├── ticket/SchemaSnapshotMapper.java # 新增
├── ticket/TicketService.java        # 新增：提交 / 详情 / 列表
├── ticket/TicketReviewService.java  # 新增：审批链
├── ticket/TicketRollbackService.java# 新增：回滚工单
├── ticket/TicketController.java     # 新增：/api/v1/tickets
├── ticket/RiskAckService.java       # 新增：风险确认落库与校验
├── ticket/exec/TicketExecutionService.java  # 新增：异步编排
├── ticket/exec/StatementFingerprint.java    # 新增：语句指纹
├── ticket/exec/DatasourceExecLock.java      # 新增：单数据源写并发 = 1
├── ticket/exec/TicketRecoveryJob.java       # 新增：过期 / 卡死
├── risk/StatementClassifier.java    # 新增：语句分类
├── risk/DdlAction.java              # 新增
├── risk/Classification.java         # 新增
├── risk/PolicyValidator.java        # 新增：16 条规范校验
├── risk/RiskLevel.java              # 新增：L0..L3
├── risk/RiskHit.java                # 新增
├── risk/RiskAssessment.java         # 新增：等级 + 命中 + 快照哈希
├── risk/RiskEngine.java             # 新增：合并分类 / 规范 / 阈值 / 窗口
├── risk/RiskProperties.java         # 新增：application.yml 阈值
└── risk/ExecWindow.java             # 新增：非窗口期判定

platform-control/src/test/java/com/badc/sql/control/support/
├── TicketStubExecutor.java          # 新增：工单类 IT 共用的执行器桩
└── TicketITBase.java                # 新增：工单类 IT 共用的 HTTP / 轮询 / 断言助手
platform-control/src/test/resources/seed/
└── ticket-fixtures.sql              # 新增：ops02 / lead01（勿并入 test-fixtures.sql）
```

**共享测试支撑（避免 6 个 IT 各抄一份助手）**

`support/TicketITBase extends AbstractMySqlIT` 是工单类 IT 的基类，由任务 7 建立、后续任务按需增补方法，**不要在每个测试类里复制**：

| 任务 | 往基类里加的方法 |
|---|---|
| 7 | `postTicket(token, body)`、`status(ticketId)`、`statementsOf(ticketId)`、`body(sql, rollbackSql)` / `bodyFor(datasourceId, sql, rollbackSql)` |
| 8 | `approve(token, id)`、`reject(token, id, comment)` |
| 11 | `requestExecution(token, id)`、`awaitTerminal(id)`、`abortReason(id)`、`statementStatuses(id)`、`approvedTicket(String...)` |
| 12 | `rollback(...)`、`rollbackAs(...)`、`rollbackOf(...)`、`succeededTicket(...)` |
| 13 | `utcMinusHours(int)` |

**职责边界**：`StatementClassifier`、`PolicyValidator`、`RiskEngine` **纯文本/纯计算**，不连库、不查权限，因此可单测跑满。任何需要连目标库的判断（指纹、快照、估算）都经 `ExecutorClient` 出去。

---

### Task 1: 执行器内部接口认证

**Files:**
- Create: `platform-executor/src/main/java/com/badc/sql/executor/internal/InternalToken.java`
- Create: `platform-executor/src/main/java/com/badc/sql/executor/internal/InternalAuthFilter.java`
- Modify: `platform-executor/src/main/java/com/badc/sql/executor/ExecutorApplication.java:1-40`（注册 filter）
- Modify: `platform-executor/src/main/resources/application.yml`
- Modify: `platform-control/src/main/java/com/badc/sql/control/query/HttpExecutorClient.java:37-44`（加令牌头）
- Modify: `platform-control/src/main/resources/application.yml`
- Test: `platform-executor/src/test/java/com/badc/sql/executor/internal/InternalAuthFilterTest.java`
- Test: `platform-control/src/test/java/com/badc/sql/control/query/HttpExecutorClientTest.java`

**Interfaces:**
- Consumes: 无。
- Produces: 执行器 `/internal/**` 要求请求头 `X-Badc-Internal-Token`；`InternalToken.matches(String expected, String actual) -> boolean`（常量时间）；`HttpExecutorClient(String baseUrl, String internalToken)` 构造器（**替换原单参构造器**）。

- [ ] **Step 1: 写失败测试 — 缺令牌 401、错令牌 401、对令牌放行、未配置令牌时一律拒绝**

```java
class InternalAuthFilterTest {

    @Test void a_request_without_the_token_is_rejected() throws Exception {
        mockMvc("s3cret").perform(post("/internal/v1/validate")
                .contentType(APPLICATION_JSON).content("{\"sql\":\"select 1\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test void a_request_with_a_wrong_token_is_rejected() { /* header "wrong" → 401 */ }

    @Test void a_request_with_the_right_token_passes() { /* header "s3cret" → 200 */ }

    /** fail-closed：运维忘了配令牌，执行器不能变成"谁都能连"的裸接口。 */
    @Test void an_unconfigured_token_rejects_everything() { /* filter with "" → 401 even with header "" */ }
}
```

`mockMvc` 用 `MockMvcBuilders.webAppContextSetup` 太重建；用 `standaloneSetup(new StubController()).addFilters(filter)`，`StubController` 只声明 `@PostMapping("/internal/v1/validate")` 返回 `{}`。

- [ ] **Step 2: 运行确认失败**

Run: `.\mvnw.cmd -q -pl platform-executor -am test "-Dtest=InternalAuthFilterTest"`
Expected: FAIL — 类不存在。

- [ ] **Step 3: 实现**

`InternalToken.matches` 用 `MessageDigest.isEqual(expected.getBytes(UTF_8), actual.getBytes(UTF_8))`，**不要**用 `equals`（可比较长度泄露前缀）。任一侧为 null/空 → 返回 false。

`InternalAuthFilter extends OncePerRequestFilter`：

- `shouldNotFilter` → `!request.getRequestURI().startsWith("/internal/")`；
- 令牌取 `@Value("${badcsql.internal.token:}")`；
- 未通过 → 写 `{"code":"UNAUTHORIZED","message":"内部令牌校验失败"}`，状态 401，`Content-Type: application/json;charset=UTF-8`；
- 启动时若令牌为空 → `log.error("badcsql.internal.token 未配置：执行器将拒绝全部内部请求")`。

注册：在 `ExecutorApplication` 里 `@Bean FilterRegistrationBean<InternalAuthFilter>`，`setOrder(Ordered.HIGHEST_PRECEDENCE)`。

`application.yml`（**不给默认值**，与切片 1 的 `root/root` 教训一致）：

```yaml
badcsql:
  internal:
    token: "${BADCSQL_INTERNAL_TOKEN:}"
```

控制面侧 `HttpExecutorClient` 构造器改为 `(@Value("${badcsql.executor.base-url:http://127.0.0.1:8081}") String baseUrl, @Value("${badcsql.executor.internal-token:${BADCSQL_INTERNAL_TOKEN:}}") String internalToken)`，`RestClient.builder().defaultHeader("X-Badc-Internal-Token", internalToken)`（令牌为空也照发，由执行器拒绝）；控制面 `application.yml` 加同一段 `badcsql.executor.internal-token`。

- [ ] **Step 4: 改既有契约测试，钉住"令牌真的发出去了"**

`HttpExecutorClientTest` 两处 `new HttpExecutorClient(...)`（第 63、132 行）改为传 `"s3cret"`；`handle()` 里加 `lastToken.set(exchange.getRequestHeaders().getFirst("X-Badc-Internal-Token"))`，新增：

```java
@Test void every_internal_call_carries_the_token() throws Exception {
    reply.set(new Reply(200, "{\"ok\":true}"));
    client.validate(READONLY_CONN, "select 1", DbVersion.MYSQL_8_0);
    assertThat(lastToken.get()).isEqualTo("s3cret");
}
```

不加这条，单测可以全绿而生产上每一次内部调用都被 401 挡下。

- [ ] **Step 5: 运行确认通过并提交**

Run: `.\mvnw.cmd -q -pl platform-executor,platform-control -am test "-Dtest=InternalAuthFilterTest,HttpExecutorClientTest,ExecutorErrorsTest"`
Expected: PASS

```powershell
git add platform-executor/src platform-control/src
git commit -m "feat(executor): require an internal token on /internal/** and send it from the control plane"
```

---

### Task 2: V3 迁移与工单状态机

**Files:**
- Create: `platform-control/src/main/resources/db/migration/V3__ticket_write_path.sql`
- Create: `platform-control/src/main/java/com/badc/sql/control/ticket/TicketStatus.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/ticket/TicketStateMachine.java`
- Test: `platform-control/src/test/java/com/badc/sql/control/MigrationIT.java`（修改：断言 7 张新表）
- Test: `platform-control/src/test/java/com/badc/sql/control/ticket/TicketStateMachineTest.java`

**Interfaces:**
- Consumes: 无。
- Produces: 表 `ticket`、`ticket_statement`、`ticket_review`、`ticket_event`、`schema_snapshot`、`risk_acknowledgement`、`datasource_exec_lock`；`enum TicketStatus`（13 值）；`TicketStateMachine.canTransition(TicketStatus from, TicketStatus to) -> boolean` 与 `assertTransition(from, to)`（非法迁移抛 `BizException(CONFLICT)`）。

- [ ] **Step 1: 写失败测试 — 状态机只允许表里的迁移**

```java
@Test void reviewing_l2_cannot_be_skipped_by_a_direct_approve() {
    assertThat(TicketStateMachine.canTransition(REVIEWING_L1, APPROVED)).isTrue();   // L1/L2 一级
    assertThat(TicketStateMachine.canTransition(DRAFT, APPROVED)).isFalse();
}

@Test void the_same_person_cannot_be_both_review_levels() { /* 由 TicketReviewService 保证，见任务 8 */ }

@Test void an_executing_ticket_cannot_be_cancelled_backwards() {
    assertThat(TicketStateMachine.canTransition(EXECUTING, CANCELLED)).isFalse();
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\mvnw.cmd -q -pl platform-control -am test "-Dtest=TicketStateMachineTest"`
Expected: FAIL — 类不存在。

- [ ] **Step 3: 写迁移与状态机**

`TicketStatus`：`DRAFT, REVIEWING_L1, REVIEWING_L2, APPROVED, EXECUTING, SUCCEEDED, PARTIAL_FAILED, FAILED, NEEDS_MANUAL_CONFIRM, REJECTED, EXPIRED, CANCELLED, ROLLED_BACK`。

`TicketStateMachine` 用一张 `Map<TicketStatus, Set<TicketStatus>>` 静态表，**只**允许：

```
DRAFT          → REVIEWING_L1, CANCELLED
REVIEWING_L1   → REVIEWING_L2, APPROVED, REJECTED, CANCELLED
REVIEWING_L2   → APPROVED, REJECTED
APPROVED       → EXECUTING, EXPIRED, CANCELLED
EXECUTING      → SUCCEEDED, PARTIAL_FAILED, FAILED, NEEDS_MANUAL_CONFIRM
SUCCEEDED      → ROLLED_BACK
PARTIAL_FAILED → ROLLED_BACK
```

`V3__ticket_write_path.sql` 建 7 表，约定沿用 V1（`datetime(3)` UTC、`utf8mb4_0900_ai_ci`、不建外键、用索引）：

- `ticket(id, system_id, module_id, datasource_id, ticket_type VARCHAR(16) COMMENT 'NORMAL|ROLLBACK', status VARCHAR(32), risk_level VARCHAR(8), risk_snapshot_json MEDIUMTEXT, risk_snapshot_hash CHAR(64), title VARCHAR(200), change_reason TEXT, rollback_plan TEXT, rollback_sql MEDIUMTEXT, statement_fingerprint CHAR(64), snapshot_enabled TINYINT(1) NOT NULL DEFAULT 1, expected_execute_at DATETIME(3) NULL, submitter_id BIGINT, origin_ticket_id BIGINT NULL, rollback_reason TEXT NULL, created_at, updated_at, approved_at, executed_at, finished_at)`；键 `uk_ticket_fingerprint(fingerprint)` 不需要，建 `KEY idx_ticket_status_created(status, created_at)`、`KEY idx_ticket_submitter(submitter_id)`、`KEY idx_ticket_datasource(datasource_id)`。
- `ticket_statement(id, ticket_id, seq_no INT, sql_text MEDIUMTEXT, statement_type VARCHAR(32), risk_level VARCHAR(8), status VARCHAR(16) COMMENT 'PENDING|RUNNING|SUCCEEDED|FAILED|SKIPPED', affected_rows BIGINT NULL, error_message TEXT NULL, elapsed_ms BIGINT NULL, executed_at DATETIME(3) NULL)`，`UNIQUE KEY uk_ticket_statement(ticket_id, seq_no)`。
- `ticket_review(id, ticket_id, review_level TINYINT, reviewer_id BIGINT, decision VARCHAR(16), comment TEXT, created_at)`，`UNIQUE KEY uk_ticket_review(ticket_id, review_level)`。
- `ticket_event(id, ticket_id, event_type VARCHAR(64), actor_id BIGINT NULL, detail_json TEXT NULL, created_at)`。
- `schema_snapshot(id, ticket_id, change_seq INT, datasource_id BIGINT, snapshot_ref VARCHAR(512), table_fingerprints_json TEXT, captured_at DATETIME(3))`。
- `risk_acknowledgement(id, ticket_id, stage VARCHAR(16) COMMENT 'SUBMIT|REVIEW|L3_REVIEW|EXECUTE', operator_id BIGINT, risk_snapshot_hash CHAR(64), ip VARCHAR(64), user_agent VARCHAR(512), created_at)`。
- `datasource_exec_lock(datasource_id BIGINT PRIMARY KEY, ticket_id BIGINT NOT NULL, acquired_at DATETIME(3) NOT NULL)` —— 主键本身就是互斥体。

- [ ] **Step 4: 运行确认通过**

Run: `.\mvnw.cmd -q -pl platform-control -am verify "-Dit.test=MigrationIT" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfailsafe.failIfNoSpecifiedTests=false"`
Expected: PASS（既有 9 表断言保留，新增 7 表断言）。

- [ ] **Step 5: 提交**

```powershell
git add platform-control/src
git commit -m "feat(ticket): add V3 schema for the write path and the ticket state machine"
```

---

### Task 3: 语句分类与结构性破坏硬规则

**Files:**
- Create: `platform-control/src/main/java/com/badc/sql/control/risk/DdlAction.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/risk/Classification.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/risk/StatementClassifier.java`
- Modify: `platform-common/src/main/java/com/badc/sql/common/sql/SqlText.java`（新增 `splitStatements`）
- Test: `platform-common/src/test/java/com/badc/sql/common/SqlTextSplitTest.java`
- Test: `platform-control/src/test/java/com/badc/sql/control/risk/StatementClassifierTest.java`

**Interfaces:**
- Consumes: `SqlText.stripLiteralsAndComments(String)`、`SqlText.codeMask(String)`（`platform-common`，已有）。
- Produces: `SqlText.splitStatements(String) -> List<String>`（按顶层 `;` 切分，字符串/注释内的 `;` 不切；空段丢弃）；`enum DdlAction { DROP_DATABASE, DROP_TABLE, TRUNCATE, RENAME_TABLE, ALTER_DROP_COLUMN, ALTER_DROP_INDEX, ALTER_MODIFY, ALTER_CONVERT_CHARSET, DROP_PARTITION, CREATE_TABLE, CREATE_INDEX, ALTER_ADD_COLUMN, ALTER_ADD_UNIQUE_INDEX, OTHER_DDL, NONE }`；`record Classification(boolean multipleStatements, String firstKeyword, DdlAction ddlAction, boolean structuralDamage, boolean writeStatement, boolean hasWhere, boolean hasLimit, boolean crossDatabase, String table)`；`StatementClassifier.classify(String sql) -> Classification`。

> `splitStatements` 是任务 12 的前提（原单回滚 SQL 可能是一串 `insert`），而"多语句"判定本身也要它，所以放在这里、放在 `platform-common`。

- [ ] **Step 1: 写失败测试 — 切分与结构性判定，两类"藏在别处"的关键字**

```java
class SqlTextSplitTest {
    @Test void a_semicolon_inside_a_string_literal_does_not_split() {
        assertThat(SqlText.splitStatements("insert into t values ('a;b')")).hasSize(1);
    }

    @Test void a_semicolon_inside_a_comment_does_not_split() {
        assertThat(SqlText.splitStatements("update t set a=1 -- x;y\n")).hasSize(1);
    }

    @Test void two_real_statements_split_into_two() {
        assertThat(SqlText.splitStatements("insert into t values (1); insert into t values (2);"))
            .containsExactly("insert into t values (1)", "insert into t values (2)");
    }
}
```

```java
@Test void a_drop_table_inside_a_string_literal_is_not_ddl() {
    Classification c = classifier.classify("update t_order set note = 'drop table x' where id = 1");
    assertThat(c.ddlAction()).isEqualTo(DdlAction.NONE);
    assertThat(c.structuralDamage()).isFalse();
    assertThat(c.writeStatement()).isTrue();
}

@Test void a_commented_out_drop_table_does_not_make_an_update_structural() {
    assertThat(classifier.classify("-- drop table t\nupdate t set a = 1 where id = 2").structuralDamage()).isFalse();
}

@Test void a_backticked_table_named_like_a_keyword_is_not_a_drop() {
    assertThat(classifier.classify("alter table `drop table` add column a int null").ddlAction())
        .isEqualTo(DdlAction.ALTER_ADD_COLUMN);
}

/** 反引号 + if exists：仍然是一次 drop table。 */
@Test void drop_table_if_exists_still_counts_as_structural_damage() {
    Classification c = classifier.classify("DROP TABLE IF EXISTS `t_order`");
    assertThat(c.ddlAction()).isEqualTo(DdlAction.DROP_TABLE);
    assertThat(c.structuralDamage()).isTrue();
    assertThat(c.table()).isEqualTo("t_order");
}

/** 最严重的子句在后面：只看第一个动作就会把 L3 判成 L1。 */
@Test void drop_column_after_add_column_still_makes_the_alter_structural() {
    assertThat(classifier.classify("alter table t_order add column b int null, drop column a").structuralDamage())
        .isTrue();
}

@Test void multiple_statements_are_flagged() {
    assertThat(classifier.classify("update t set a=1; delete from t").multipleStatements()).isTrue();
}

@Test void a_cross_database_reference_is_flagged() {
    assertThat(classifier.classify("update db2.t_order set a = 1 where id = 1").crossDatabase()).isTrue();
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\mvnw.cmd -q -pl platform-control -am test "-Dtest=StatementClassifierTest"`
Expected: FAIL — 类不存在。

- [ ] **Step 3: 实现**

先 `SqlText.stripLiteralsAndComments(sql)` 得到"代码骨架"，**所有判定都在骨架上做**；原文只用于取表名。

`SqlText.splitStatements` 用已有的 `codeMask` 定位顶层 `;`（`codeMask` 把字符串与注释涂成等长空白，因此下标与原串对齐），按下标切原串、`trim` 后丢弃空段。`Classification.multipleStatements` = `splitStatements(sql).size() > 1`；分类器**不自己 split**。

取动作序列：把骨架按空白与逗号切成 token（保留反引号标识符为一个 token），逐个匹配：

- `drop database|schema` → `DROP_DATABASE`；`drop table` → `DROP_TABLE`；`truncate` → `TRUNCATE`；`rename table` → `RENAME_TABLE`；`drop partition` → `DROP_PARTITION`；
- `alter table` 之后扫描**全部**子句动词：`drop column` → `ALTER_DROP_COLUMN`、`drop index|key` → `ALTER_DROP_INDEX`、`add unique index|add unique key` → `ALTER_ADD_UNIQUE_INDEX`、`add column|add` → `ALTER_ADD_COLUMN`、`convert to charset` → `ALTER_CONVERT_CHARSET`、`modify` → `ALTER_MODIFY`（是否收窄由任务 4 判，分类器只标动作）；
- 一个 `alter` 里多个动作用 `EnumSet`，`ddlAction()` 返回**严重度最高**的那个（严重度顺序按 `DdlAction` 声明顺序，`DROP_DATABASE` 最高）。

`structuralDamage()` = `ddlAction ∈ {DROP_TABLE, TRUNCATE, RENAME_TABLE, ALTER_DROP_COLUMN, ALTER_DROP_INDEX, ALTER_CONVERT_CHARSET, DROP_PARTITION}` **或**（`ALTER_MODIFY` 且任务 4 判为收窄）。收窄判定留在任务 4（需要列类型文本），分类器只保证 `ALTER_MODIFY` 动作被识别。

`crossDatabase()` = 表名 token 含 `.` 且点号前不是反引号内的已有表名——简化：骨架里出现 `\w+\.\w+` 形式且左侧不是 `select`/`insert` 等关键字。

- [ ] **Step 4: 运行确认通过**

Run: `.\mvnw.cmd -q -pl platform-common test "-Dtest=SqlTextSplitTest"`，再 `.\mvnw.cmd -q -pl platform-control -am test "-Dtest=StatementClassifierTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```powershell
git add platform-common/src platform-control/src
git commit -m "feat(risk): split statements ignoring literals and pin the nine structural-damage actions"
```

---

### Task 4: 规范校验（16 条）

**Files:**
- Create: `platform-control/src/main/java/com/badc/sql/control/risk/RiskLevel.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/risk/RiskHit.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/risk/PolicyValidator.java`
- Test: `platform-control/src/test/java/com/badc/sql/control/risk/PolicyValidatorTest.java`

**Interfaces:**
- Consumes: `StatementClassifier.classify`、`Classification`（任务 3）。
- Produces:
  - `enum RiskLevel { L0, L1, L2, L3 }`，带 `static RiskLevel stricter(RiskLevel a, RiskLevel b)`（取序号大者；null 视为 L0）
  - `enum Discipline { BLOCK, PIN_LEVEL, WARN, INFO }`
  - `record RiskHit(String ruleCode, Discipline discipline, RiskLevel level, String message)` —— `level` 只在 `PIN_LEVEL` 时有意义，`WARN`/`INFO`/`BLOCK` 传 `null`（类注释写明）
  - `PolicyValidator.validate(String sql, Classification c, Map<String, Map<String, String>> oldColumnTypesOrNull) -> List<RiskHit>`
  - `oldColumnTypesOrNull` 的形态是 `{表名: {列名: 类型文本}}`，来自结构快照；**为 null 时不得报错**，按最严分支处理（见 Step 3）

- [ ] **Step 1: 写失败测试 — BLOCK 与 PIN_LEVEL 两种口径不能混**

```java
@Test void drop_database_is_blocked_at_submit() {
    assertThat(validator.validate("drop database order_db", classifier.classify("drop database order_db"), null))
        .singleElement()
        .satisfies(hit -> {
            assertThat(hit.discipline()).isEqualTo(Discipline.BLOCK);
            assertThat(hit.ruleCode()).isEqualTo("DROP_DATABASE");
        });
}

@Test void multiple_statements_in_one_string_are_blocked() { /* "update t set a=1; delete from t" → BLOCK MULTI_STATEMENT */ }

@Test void an_update_without_where_pins_l3_but_does_not_block() {
    assertThat(validator.validate("update t_order set a = 1", classifier.classify("update t_order set a = 1"), null))
        .anySatisfy(hit -> {
            assertThat(hit.ruleCode()).isEqualTo("WRITE_WITHOUT_WHERE");
            assertThat(hit.discipline()).isEqualTo(Discipline.PIN_LEVEL);
            assertThat(hit.level()).isEqualTo(RiskLevel.L3);
        });
}

@Test void drop_table_and_truncate_pin_l3() { /* 各一条 PIN_LEVEL L3 */ }

/** 有旧类型且是放宽：不算收窄，不得命中 ALTER_MODIFY_NARROWED。 */
@Test void widening_a_column_type_is_not_narrowing_when_the_old_type_is_known() {
    Map<String, Map<String, String>> old = Map.of("t", Map.of("c", "varchar(64)"));
    assertThat(validator.validate("alter table t modify column c varchar(255) not null",
            classifier.classify("alter table t modify column c varchar(255) not null"), old))
        .noneSatisfy(hit -> assertThat(hit.ruleCode()).isEqualTo("ALTER_MODIFY_NARROWED"));
}

/** 有旧类型且是收窄：命中 L3。 */
@Test void narrowing_a_column_type_pins_l3_when_the_old_type_is_known() {
    Map<String, Map<String, String>> old = Map.of("t", Map.of("c", "varchar(255)"));
    assertThat(validator.validate("alter table t modify column c varchar(64) not null",
            classifier.classify("alter table t modify column c varchar(64) not null"), old))
        .anySatisfy(hit -> {
            assertThat(hit.ruleCode()).isEqualTo("ALTER_MODIFY_NARROWED");
            assertThat(hit.level()).isEqualTo(RiskLevel.L3);
        });
}

/** 没有旧类型可比：一律按最严，并说明理由——这条用例是"保守分支"的唯一证据，不能省。 */
@Test void an_unknown_old_type_falls_back_to_l3_with_a_reason() {
    assertThat(validator.validate("alter table t modify column c varchar(255) not null",
            classifier.classify("alter table t modify column c varchar(255) not null"), null))
        .anySatisfy(hit -> {
            assertThat(hit.ruleCode()).isEqualTo("ALTER_MODIFY_NARROWED");
            assertThat(hit.message()).contains("无法判定");
        });
}

@Test void select_star_is_only_a_warning() { /* WARN SELECT_STAR，level 为 null */ }
```

- [ ] **Step 2: 运行确认失败**

Run: `.\mvnw.cmd -q -pl platform-control -am test "-Dtest=PolicyValidatorTest"`
Expected: FAIL — 类不存在。

- [ ] **Step 3: 实现**

规则表按 spec §FR-10 逐条落地，`ruleCode` 即测试里断言的名字：

`BLOCK`：`DROP_DATABASE`、`MULTI_STATEMENT`。

`PIN_LEVEL L3`：`DROP_TABLE`、`TRUNCATE`、`RENAME_TABLE`、`ALTER_DROP_COLUMN`、`ALTER_DROP_INDEX`、`ALTER_MODIFY_NARROWED`、`ALTER_CONVERT_CHARSET`、`DROP_PARTITION`、`WRITE_WITHOUT_WHERE`。

`WARN`：`SELECT_STAR`、`SELECT_WITHOUT_LIMIT`（且无聚合）、`LEADING_WILDCARD_LIKE`、`IMPLICIT_CAST`、`ORDER_BY_RAND`、`VERSION_MISMATCH_HINT`。

`INFO`：`KEYWORD_NOT_UPPERCASE`、`INDENTATION_MESSY`（后者只做"行首混用空格与制表符"这一条可判定的规则，不做风格评分）。

收窄判定（**这是本任务最容易做错的一处**）：收窄是"前后对比"，而一句 `alter table t modify column c varchar(64)` 里没有旧类型。所以判定必须依赖 `oldColumnTypesOrNull`：

- **给了旧类型**：按原文取 `modify [column] <name> <type>` 与 `{table: {column: oldType}}` 比对——`bigint → int|smallint|tinyint`、`varchar(n) → varchar(m)` 且 `m < n`、`char(n) → char(m)` 且 `m < n`、`decimal(p,s) → decimal(p',s')` 且 `p' < p` 为收窄；放宽或换类型但无法证明收窄 → 不命中。
- **没给旧类型（`null`）或查不到该列**：`ALTER_MODIFY`/`ALTER_ADD_COLUMN` 之外的任何 `modify`/`change` 一律 `PIN_LEVEL L3`，消息含 `无法判定是否收窄`。

任务 7 会从结构快照填这个参数；在它填上之前，系统对所有 `modify` 都按 L3 处理——这是刻意的保守，不是遗漏。**两条路径都要有用例**，否则这个设计会静悄悄退化成"一律 L3"而无人察觉。

- [ ] **Step 4: 运行确认通过**

Run: `.\mvnw.cmd -q -pl platform-control -am test "-Dtest=PolicyValidatorTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```powershell
git add platform-control/src
git commit -m "feat(risk): add the sixteen policy rules with BLOCK vs PIN_LEVEL discipline"
```

---

### Task 5: 执行器侧结构快照、指纹与 EXPLAIN 估算

**Files:**
- Create: `platform-common/src/main/java/com/badc/sql/common/dto/SnapshotResult.java`
- Create: `platform-common/src/main/java/com/badc/sql/common/dto/ExplainEstimate.java`
- Create: `platform-executor/src/main/java/com/badc/sql/executor/schema/SnapshotPaths.java`
- Create: `platform-executor/src/main/java/com/badc/sql/executor/schema/SchemaSnapshotter.java`
- Create: `platform-executor/src/main/java/com/badc/sql/executor/exec/ExplainRunner.java`
- Modify: `platform-executor/src/main/java/com/badc/sql/executor/api/ExecutorController.java`
- Modify: `platform-control/src/main/java/com/badc/sql/control/query/ExecutorClient.java`（加 `fingerprint`）
- Modify: `platform-control/src/main/java/com/badc/sql/control/query/HttpExecutorClient.java`
- Modify: `platform-control/src/test/java/com/badc/sql/control/query/QueryControllerIT.java:466-536`（桩补新方法）
- Modify: `platform-control/src/test/java/com/badc/sql/control/query/HttpExecutorClientTest.java`（加一条 fingerprint 契约用例）
- Create: `platform-control/src/test/java/com/badc/sql/control/support/TicketStubExecutor.java`（工单类 IT 共用的执行器桩）
- Test: `platform-executor/src/test/java/com/badc/sql/executor/schema/SnapshotPathsTest.java`
- Test: `platform-executor/src/test/java/com/badc/sql/executor/schema/SchemaSnapshotterIT.java`

**Interfaces:**
- Consumes: `ConnConfig`、`MySqlAdapter.url(ConnConfig)`。
- Produces:
  - `record SnapshotResult(String ref, Map<String, String> tableFingerprints, long byteSize)`
  - `record ExplainEstimate(long estimatedRows)`（`-1` 表示无法估算）
  - `SchemaSnapshotter.capture(ConnConfig conn, List<String> tables, String relativeRef) -> SnapshotResult`
  - `SchemaSnapshotter.fingerprint(ConnConfig conn, List<String> tables) -> Map<String, String>`
  - `ExplainRunner.estimate(ConnConfig conn, String sql) -> ExplainEstimate`（`-1` 表示无法估算）
  - `SnapshotPaths.resolve(String root, String relativeRef) -> Path`
  - `ExecutorClient.fingerprint(ConnConfig conn, List<String> tables) -> Map<String, String>`
  - 执行器端点：`POST /internal/v1/snapshot`、`POST /internal/v1/fingerprint`、`POST /internal/v1/explain`

- [ ] **Step 1: 写失败测试 — 路径逃逸必须先被挡下**

```java
class SnapshotPathsTest {
    @Test void a_relative_ref_is_joined_under_the_root() {
        assertThat(SnapshotPaths.resolve("/data/snapshot", "1/101/7/1"))
            .isEqualTo(Path.of("/data/snapshot/1/101/7/1"));
    }

    @Test void dot_dot_is_refused() {
        assertThatThrownBy(() -> SnapshotPaths.resolve("/data/snapshot", "1/../../etc"))
            .isInstanceOf(BizException.class).hasMessageContaining("路径");
    }

    @Test void an_absolute_ref_is_refused() {
        assertThatThrownBy(() -> SnapshotPaths.resolve("/data/snapshot", "/etc/passwd"))
            .isInstanceOf(BizException.class);
    }

    /** 前缀必须按路径元素比较：`/data/snapshotX` 不能冒充 `/data/snapshot`。 */
    @Test void a_sibling_directory_sharing_the_prefix_is_refused() {
        assertThatThrownBy(() -> SnapshotPaths.resolve("/data/snapshot", "../snapshotX/1"))
            .isInstanceOf(BizException.class);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\mvnw.cmd -q -pl platform-executor -am test "-Dtest=SnapshotPathsTest"`
Expected: FAIL — 类不存在。

- [ ] **Step 3: 实现**

`SnapshotPaths.resolve`：`Path.of(relativeRef).normalize()` 后必须仍以 `Path.of(root).normalize()` 为前缀，且 `relativeRef` 不含 `\`、不以 `/` 或 `:` 开头。不满足 → `BizException(INVALID_ARGUMENT, "快照路径不合法")`。

**表名永远不参与路径**：落盘路径只由 `relativeRef`（`{systemId}/{datasourceId}/{ticketId}/{changeSeq}`）决定，表名只出现在 `schema.sql` / `schema.json` 的内容里。原计划里的 `SnapshotPaths.fileName` 已删除——本布局没有逐表文件，留一个无人调用的"表名→文件名"映射会让人误以为路径安全靠它保证。

`SchemaSnapshotter.capture`：对每个表执行

```sql
show create table `<table>`
```

代码里 `\`` 与表名之间的反引号必须在表名内转义（`` ` `` → ` `` `），否则 `` t`x `` 这种名字会破坏语句。抓取内容写入单个 `schema.sql`（各表 `SHOW CREATE TABLE` 原文，用 `-- <table>` 分隔）与 `schema.json`（结构化：`{table, createSql, columns:[{name,type,nullable,default,comment}], indexes:[{name,unique,columns}], triggers:[{name,statement}], rowCountEstimate}`，来自 `information_schema`）。

`fingerprint`：对每表取 `SHOW CREATE TABLE` 规范化（去行尾空白、统一 `\n`）后 `sha256`，再对 `(table, hash)` 排序拼成整串 `sha256` 作为该表的指纹值，返回 `Map<table, sha256>`。**只读 `SHOW CREATE TABLE` + `information_schema`，不扫表。**

`ExplainRunner.estimate(ConnConfig conn, String sql)`：执行 `explain <sql>`，读全部行的 `rows` 列求和；任一行为 NULL 或结果为空 → 返回 `ExplainEstimate(-1)`。**不得**执行原语句。

`ExecutorController` 新增三个方法，入参记录：

```java
public record SnapshotCall(ConnConfig conn, List<String> tables, String relativeRef) {}
public record FingerprintCall(ConnConfig conn, List<String> tables) {}
public record ExplainCall(ConnConfig conn, String sql) {}
```

`SchemaSnapshotter` 的根目录从配置取：`@Value("${badcsql.snapshot.root:/data/snapshot}")`，测试里用 `@TempDir` 显式构造。

控制面侧：`ExecutorClient` 加 `Map<String, String> fingerprint(ConnConfig conn, List<String> tables)`，`HttpExecutorClient` 打 `POST /internal/v1/fingerprint` 并复用既有 `post(...)`。**这一步会打断所有 `ExecutorClient` 的测试桩**（至少 `QueryControllerIT.StubExecutor`）——必须同时补上方法体，否则控制面测试编译不过；那里只做 `throw new UnsupportedOperationException("查询测试不走这个入口")`，不铺无用的假数据。

工单类 IT 需要的是一个真正可用的桩，且会被任务 7/8/11/13 四个测试类共用。在 `support/TicketStubExecutor.java` 建一份，本任务先给到 `fingerprint`：

```java
public class TicketStubExecutor implements ExecutorClient {

    /** 提交与执行两次取指纹默认返回同一组值，正常路径因此天然通过；要造漂移就改这个字段。 */
    public Map<String, String> fingerprints = new LinkedHashMap<>(Map.of("t_order", "fp-1"));

    /** 最近一次 fingerprint 请求的表名，用来证明提交时确实抓了指纹。 */
    public List<String> lastFingerprintTables = List.of();

    @Override public RawResult query(ConnConfig conn, String sql, int maxRows) { throw new UnsupportedOperationException(); }
    @Override public Capability probe(ConnConfig conn, ConnConfig onlineDdlOrNull) { throw new UnsupportedOperationException(); }
    @Override public ValidationResult validate(ConnConfig conn, String sql, DbVersion v) { return ValidationResult.success(); }

    @Override public Map<String, String> fingerprint(ConnConfig conn, List<String> tables) {
        this.lastFingerprintTables = List.copyOf(tables);
        return fingerprints;
    }
}
```

配一个 `@TestConfiguration` 把它注册成 `ExecutorClient` 的 bean（同 `QueryControllerIT.StubExecutorConfig` 的写法），后续任务只需往里加方法。

`HttpExecutorClientTest` 加一条契约用例，钉住路径与请求体字段名（这个测试类用真实 JDK `HttpServer`，路径写错会立刻红）：

```java
@Test void fingerprint_posts_the_table_list_and_parses_the_map() throws Exception {
    reply.set(new Reply(200, "{\"t_order\":\"abc123\"}"));
    assertThat(client.fingerprint(READONLY_CONN, List.of("t_order"))).containsEntry("t_order", "abc123");
    assertThat(lastPath.get()).isEqualTo("/internal/v1/fingerprint");
    assertThat(MAPPER.readTree(lastBody.get()).path("tables").get(0).asText()).isEqualTo("t_order");
}
```

- [ ] **Step 4: 集成测试 — 真实 MySQL 上抓一次快照**

执行器侧沿用既有风格（见 `QueryRunnerIT`）：**纯 JUnit，不起 Spring 上下文**，固定 schema 名、硬编码 `root/root`、裸 JDBC 建表与断言。

```java
class SchemaSnapshotterIT {
    private static final String SCHEMA = "badc_test_snapshot";
    private static final String USER = "root";
    private static final String PASS = "root";

    @TempDir static Path tempRoot;
    private static ConnConfig conn;
    private static SchemaSnapshotter snapshotter;

    @BeforeAll
    static void createSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(MySqlAdapter.url(conn("")), USER, PASS);
             Statement s = c.createStatement()) {
            s.execute("drop database if exists " + SCHEMA);
            s.execute("create database " + SCHEMA + " default character set utf8mb4");
        }
        conn = conn(SCHEMA);
        snapshotter = new SchemaSnapshotter(tempRoot.toString());
    }

    @AfterAll
    static void dropSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(MySqlAdapter.url(conn("")), USER, PASS);
             Statement s = c.createStatement()) {
            s.execute("drop database if exists " + SCHEMA);
        }
    }

    @Test void capturing_a_snapshot_writes_both_files_and_a_stable_fingerprint() throws Exception {
        execute("create table t_order (id bigint primary key, remark varchar(64) null)");

        SnapshotResult first = snapshotter.capture(conn, List.of("t_order"), "1/101/7/1");

        assertThat(first.tableFingerprints()).containsKey("t_order");
        assertThat(first.ref()).isEqualTo("1/101/7/1");
        assertThat(tempRoot.resolve("1/101/7/1/schema.sql")).exists();
        assertThat(tempRoot.resolve("1/101/7/1/schema.json")).exists();
        assertThat(first.byteSize()).isLessThan(100 * 1024);
        assertThat(snapshotter.fingerprint(conn, List.of("t_order")).get("t_order"))
            .as("同一结构两次取指纹必须一致").isEqualTo(first.tableFingerprints().get("t_order"));
    }

    @Test void adding_a_column_changes_the_fingerprint() throws Exception {
        execute("create table t2 (id bigint primary key)");
        String before = snapshotter.fingerprint(conn, List.of("t2")).get("t2");
        execute("alter table t2 add column extra int null");
        assertThat(snapshotter.fingerprint(conn, List.of("t2")).get("t2"))
            .as("加列之后指纹必须变，否则漂移检测形同虚设").isNotEqualTo(before);
    }

    /** 新建表 / drop 之后的表：不存在不是失败，返回空指纹，由调用方记录"前置状态为空"。 */
    @Test void a_missing_table_yields_an_empty_fingerprint_instead_of_failing() {
        assertThat(snapshotter.fingerprint(conn, List.of("does_not_exist"))).isEmpty();
    }

    @Test void an_explain_estimate_reports_the_rows_of_a_looked_up_statement() throws Exception {
        execute("create table t3 (id bigint primary key)");
        assertThat(new ExplainRunner().estimate(conn, "update t3 set id = 1 where id = 1").estimatedRows())
            .isGreaterThanOrEqualTo(0L);
        assertThat(new ExplainRunner().estimate(conn, "update no_such_table set id = 1").estimatedRows())
            .as("语句本身不合法时给 -1，由调用方按最严处理").isEqualTo(-1L);
    }

    private static void execute(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(MySqlAdapter.url(conn), USER, PASS);
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
```

`@TempDir` 用静态字段时 JUnit 会在 `@BeforeAll` 之前注入，可安全用于 `new SchemaSnapshotter(tempRoot.toString())`。

- [ ] **Step 5: 运行确认通过并提交**

Run: `.\mvnw.cmd -q -pl platform-executor -am verify "-Dit.test=SchemaSnapshotterIT" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfailsafe.failIfNoSpecifiedTests=false"`，再 `.\mvnw.cmd -q test "-Dtest=SnapshotPathsTest,HttpExecutorClientTest"`
Expected: PASS

```powershell
git add platform-common/src platform-executor/src platform-control/src
git commit -m "feat(executor): capture schema snapshots and fingerprints, and estimate affected rows"
```

---

### Task 6: 风险分级引擎

**Files:**
- Create: `platform-control/src/main/java/com/badc/sql/control/risk/RiskAssessment.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/risk/RiskProperties.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/risk/ExecWindow.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/risk/RiskEngine.java`
- Modify: `platform-control/src/main/java/com/badc/sql/control/query/ExecutorClient.java`（加 `explain`）
- Modify: `platform-control/src/main/java/com/badc/sql/control/query/HttpExecutorClient.java`
- Test: `platform-control/src/test/java/com/badc/sql/control/risk/RiskEngineTest.java`

**Interfaces:**
- Consumes: `StatementClassifier`、`PolicyValidator`（任务 3、4），`ExecutorClient.explain(ConnConfig, String) -> ExplainEstimate`。
- Produces:
  - `record RiskAssessment(RiskLevel level, List<RiskHit> hits, String snapshotHash)`
  - `RiskEngine.assess(RiskRequest request) -> RiskAssessment`；`record RiskRequest(String sql, Classification classification, ExplainEstimate estimate, boolean withinWindow, boolean snapshotEnabled, Map<String, Map<String, String>> oldColumnTypesOrNull)` —— 最后一个参数原样透传给 `PolicyValidator.validate`，任务 7 从结构快照填入；为 `null` 时收窄判定走"无法判定→L3"的保守分支
  - `RiskAssessment.blocked()` → `hits` 中存在 `Discipline.BLOCK`
  - `ExecWindow.isWithin(Instant at) -> boolean`
  - `RiskProperties`：`badcsql.risk.l1-max-rows=100`、`l2-max-rows=1000`、`window-start=20:00`、`window-end=06:00`、`window-zone=Asia/Shanghai`；另有 `RiskProperties.relaxed()` 静态工厂（阈值放到最大，仅供"硬规则不可放宽"的对照用例）

- [ ] **Step 1: 写失败测试 — 硬规则不因配置放宽**

测试类的固定脚手架（`engine` 用默认属性；`engineWith` 用于放宽阈值的对照面）：

```java
private final StatementClassifier classifier = new StatementClassifier();
private final RiskEngine engine = new RiskEngine(new PolicyValidator(), new RiskProperties());

private RiskEngine engineWith(RiskProperties p) { return new RiskEngine(new PolicyValidator(), p); }

private RiskRequest req(String sql, boolean withinWindow) {
    return new RiskRequest(sql, classifier.classify(sql), new ExplainEstimate(-1), withinWindow, true, null);
}

private RiskRequest reqWithEstimate(String sql, long rows, boolean withinWindow) {
    return new RiskRequest(sql, classifier.classify(sql), new ExplainEstimate(rows), withinWindow, true, null);
}
```

```java
@Test void a_structural_drop_is_l3_even_when_every_threshold_is_relaxed() {
    assertThat(engineWith(RiskProperties.relaxed()).assess(req("drop table t_order", true)).level())
        .isEqualTo(RiskLevel.L3);
}

@Test void an_update_without_where_is_l3_but_not_blocked() {
    RiskAssessment a = engine.assess(req("update t_order set a = 1", true));
    assertThat(a.level()).isEqualTo(RiskLevel.L3);
    assertThat(a.blocked()).isFalse();
}

@Test void an_update_with_a_small_estimate_is_l1_inside_the_window() {
    assertThat(engine.assess(reqWithEstimate("update t_order set a = 1 where id = 1", 1L, true)).level())
        .isEqualTo(RiskLevel.L1);
}

@Test void an_update_between_the_two_thresholds_is_l2() {
    assertThat(engine.assess(reqWithEstimate("update t set a = 1 where b = 2", 500L, true)).level())
        .isEqualTo(RiskLevel.L2);
}

@Test void an_update_over_a_thousand_rows_is_l3() {
    assertThat(engine.assess(reqWithEstimate("update t set a = 1 where b = 2", 5_000L, true)).level())
        .isEqualTo(RiskLevel.L3);
}

/** 估算不出来就不能当 L1：宁可多一级审批。 */
@Test void an_unknown_estimate_grades_an_update_as_l3() {
    assertThat(engine.assess(reqWithEstimate("update t join u on u.id = t.id set t.a = 1", -1L, true)).level())
        .isEqualTo(RiskLevel.L3);
}

@Test void outside_the_window_a_small_update_is_escalated_by_one_level() {
    assertThat(engine.assess(reqWithEstimate("update t set a = 1 where id = 1", 1L, false)).level())
        .isEqualTo(RiskLevel.L2);
}

@Test void outside_the_window_an_l2_update_becomes_l3() {
    assertThat(engine.assess(reqWithEstimate("update t set a = 1 where b = 2", 500L, false)).level())
        .isEqualTo(RiskLevel.L3);
}

/** 结构性破坏已在最高级，非窗口期不再叠加，只加一条终审确认。 */
@Test void a_structural_l3_is_not_escalated_again_outside_the_window() {
    RiskAssessment a = engine.assess(req("drop table t_order", false));
    assertThat(a.level()).isEqualTo(RiskLevel.L3);
    assertThat(a.hits()).anySatisfy(h -> assertThat(h.ruleCode()).isEqualTo("OUT_OF_WINDOW_ACK"));
}

@Test void a_blocking_rule_is_reported_and_the_ticket_cannot_be_submitted() {
    assertThat(engine.assess(req("drop database x", true)).blocked()).isTrue();
}

/** 快照关闭升 L3（本切片快照不可关，保留判定以便切片 3 复用）。 */
@Test void a_disabled_snapshot_grades_l3_and_is_recorded_as_a_hit() {
    RiskRequest r = new RiskRequest("update t set a = 1 where id = 1",
            classifier.classify("update t set a = 1 where id = 1"), new ExplainEstimate(1), true, false, null);
    RiskAssessment a = engine.assess(r);
    assertThat(a.level()).isEqualTo(RiskLevel.L3);
    assertThat(a.hits()).anySatisfy(h -> assertThat(h.ruleCode()).isEqualTo("SNAPSHOT_DISABLED"));
}

/** 改了一个字但等级没变：哈希必须变，否则"风险未变"校验会放行被改过的语句。 */
@Test void the_snapshot_hash_changes_when_the_sql_changes() {
    assertThat(engine.assess(reqWithEstimate("update t set a = 1 where id = 1", 1L, true)).snapshotHash())
        .isNotEqualTo(engine.assess(reqWithEstimate("update t set a = 2 where id = 1", 1L, true)).snapshotHash());
}

@Test void the_snapshot_hash_is_stable_for_identical_input() {
    assertThat(engine.assess(req("drop table t", true)).snapshotHash())
        .isEqualTo(engine.assess(req("drop table t", true)).snapshotHash());
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\mvnw.cmd -q -pl platform-control -am test "-Dtest=RiskEngineTest"`
Expected: FAIL — 类不存在。

- [ ] **Step 3: 实现**

`assess` 的顺序是固定的，不得调换：

1. `PolicyValidator` 出 `hits`；若有 `BLOCK` → 直接返回 `L3` + `blocked()`，不往下走（BLOCK 的语句本来就进不到审批）。
2. 基础等级：只读 `L0`；`structuralDamage` → `L3`；否则按语句与估算定 `L1/L2/L3`：
   - `insert`（单条或估算 ≤100）→ `L1`；估算 101–1000 → `L2`；>1000 → `L3`；
   - `update`/`delete`：估算 `1..l1Max` → `L1`；`l1Max+1..l2Max` → `L2`；`>l2Max` 或估算为 `-1` → `L3`；
   - `create table` / `alter add column`（可空）/ `create index` → `L1`；`alter add unique index` / `alter modify`（非收窄）→ `L2`；
   - `crossDatabase` → 至少 `L3`；
   - 无法归类的写语句 → `L3`（默认从严）。
3. `PIN_LEVEL` 命中把等级**抬到**其 `level`（只能抬升，不能降低）。
4. 非窗口期：若当前等级是 `L1` 或 `L2` → +1 级；若已是 `L3` → 追加 `OUT_OF_WINDOW_ACK` 命中且等级保持 `L3`。
5. `snapshotEnabled == false` → 抬到 `L3` 并加 `SNAPSHOT_DISABLED` 命中。

`RiskAssessment.snapshotHash` = `sha256` over `level.name() + "|" + sortedDistinct(ruleCodes) + "|" + sqlSha256`。**必须包含 sql 哈希**：研发改了语句但风险等级恰好不变时，哈希必须变，否则任务 9 的"风险未变"校验会放行一条被改过的语句。

`ExecWindow.isWithin(Instant at)`：把 `at` 转到 `window-zone`，取 `LocalTime`；`start <= end` 时区间为 `[start, end)`，跨零点时（`20:00`–`06:00`）为 `>= start || < end`。

`ExecutorClient` 加 `ExplainEstimate explain(ConnConfig conn, String sql)`，`HttpExecutorClient` 实现 `POST /internal/v1/explain`，复用既有 `post(...)` 与 `ExecutorErrors`。两个桩都要补：`QueryControllerIT.StubExecutor` 抛 `UnsupportedOperationException`；`support/TicketStubExecutor` 加可设置字段 `ExplainEstimate explainEstimate = new ExplainEstimate(1)`，默认让 `update ... where id = 1` 落到 L1。

`RiskProperties` 是 `@ConfigurationProperties(prefix = "badcsql.risk")` 的 record，在 `ControlApplication` 上加 `@ConfigurationPropertiesScan`（或 `@EnableConfigurationProperties(RiskProperties.class)`），否则 `RiskEngine` 注入不到它、单测之外的路径会在启动期失败。`relaxed()` 把 `l1MaxRows`/`l2MaxRows` 设为 `Integer.MAX_VALUE`，窗口设为 `00:00`–`23:59`。

- [ ] **Step 4: 运行确认通过**

Run: `.\mvnw.cmd -q -pl platform-control -am test "-Dtest=RiskEngineTest,StatementClassifierTest,PolicyValidatorTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```powershell
git add platform-control/src
git commit -m "feat(risk): grade statements with hard rules, estimates and window escalation"
```

---

### Task 7: 工单提交、详情与列表

**Files:**
- Create: `platform-control/src/main/java/com/badc/sql/control/ticket/TicketEntity.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/ticket/TicketMapper.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/ticket/TicketStatementMapper.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/ticket/TicketEventMapper.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/ticket/SchemaSnapshotMapper.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/ticket/exec/StatementFingerprint.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/ticket/TicketService.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/ticket/TicketController.java`
- Create: `platform-control/src/test/resources/seed/ticket-fixtures.sql`
- Test: `platform-control/src/test/java/com/badc/sql/control/ticket/StatementFingerprintTest.java`
- Test: `platform-control/src/test/java/com/badc/sql/control/ticket/TicketSubmitIT.java`

> **不要动 `seed/test-fixtures.sql`**。加用户或归属会连带打断 `MigrationIT` 的用户集合断言、`VisibilityServiceIT` 与 `GrantControllerIT` 的归属断言——切片 1 已经被共享夹具的连带失效坑过一次（`admin01` 那次）。工单相关的新增人员放进独立的 `seed/ticket-fixtures.sql`，只由工单类 IT 加载：
>
> ```sql
> -- 工单链路专用：ops02 是系统 1 的主运维（能审系统 1），lead01 是系统 1 的组长（能终审）。
> -- ops01 保持"系统 2 的运维"，正好用来验跨系统不能审。
> INSERT INTO sys_user (id, login_name, password_hash, role, display_name) VALUES
>     (4, 'ops02',  '$2b$10$TznpaUV2xFOaY3aN9B0lpe0H6sbVMVrDBekF4fxgICSl5BcU9SVGW', 'PRIMARY_OPS', '主运维二号'),
>     (5, 'lead01', '$2b$10$TznpaUV2xFOaY3aN9B0lpe0H6sbVMVrDBekF4fxgICSl5BcU9SVGW', 'OPS_LEAD',    '运维组长');
> INSERT INTO sys_system_operator (id, system_id, user_id, operator_type) VALUES
>     (10, 1, 4, 'PRIMARY'),
>     (11, 1, 5, 'LEAD');
> ```
>
> 密码同 `Passw0rd!`；`OPS02_TOKEN = JwtTestSupport.token(4L, "ops02")`、`LEAD01_TOKEN = JwtTestSupport.token(5L, "lead01")`。

**Interfaces:**
- Consumes: `VisibilityService.assertVisible`、`ConnAccountService.readonlyConnConfig`（提交期指纹只需要只读账号）、`ExecutorClient.fingerprint`、`RiskEngine.assess`、`AuditService.record`。
- Produces:
  - `StatementFingerprint.of(long datasourceId, List<String> statements) -> String`（sha256 over `datasourceId` + 序号 + 每条 `stripTrailing` 后的原文）
  - `TicketService.submit(long submitterId, SubmitCommand cmd) -> long ticketId`；`record SubmitCommand(long datasourceId, String title, String changeReason, String rollbackPlan, String rollbackSql, List<String> statements, Instant expectedExecuteAt)`
  - `TicketService.detail(long userId, long ticketId) -> TicketDetail`；`TicketService.list(long userId, int page, int size) -> List<TicketSummary>`
  - `POST /api/v1/tickets` → `{id, status, riskLevel, statements:[{seq, statementType, riskLevel}]}`（201）
  - `GET /api/v1/tickets`、`GET /api/v1/tickets/{id}`

- [ ] **Step 1: 写失败测试 — 必填与指纹**

```java
class StatementFingerprintTest {
    @Test void the_same_statements_and_datasource_produce_the_same_fingerprint() {
        assertThat(StatementFingerprint.of(101, List.of("update t set a = 1")))
            .isEqualTo(StatementFingerprint.of(101, List.of("update t set a = 1")));
    }

    @Test void changing_one_character_changes_the_fingerprint() {
        assertThat(StatementFingerprint.of(101, List.of("update t set a = 1")))
            .isNotEqualTo(StatementFingerprint.of(101, List.of("update t set a = 2")));
    }

    @Test void reordering_statements_changes_the_fingerprint() {
        assertThat(StatementFingerprint.of(101, List.of("a", "b")))
            .isNotEqualTo(StatementFingerprint.of(101, List.of("b", "a")));
    }

    /** 换库就是换工单：数据源变了指纹必须变，执行时才拦得住"审核后换库"。 */
    @Test void retargeting_another_datasource_changes_the_fingerprint() {
        assertThat(StatementFingerprint.of(101, List.of("update t set a = 1")))
            .isNotEqualTo(StatementFingerprint.of(102, List.of("update t set a = 1")));
    }

    @Test void trailing_newlines_do_not_change_the_fingerprint() {
        assertThat(StatementFingerprint.of(101, List.of("select 1\n")))
            .isEqualTo(StatementFingerprint.of(101, List.of("select 1")));
    }
}
```

```java
class TicketSubmitIT extends AbstractMySqlIT {   // @SpringBootTest(RANDOM_PORT) + @Import(TicketStubExecutorConfig.class)
    @BeforeAll void loadFixtures() {   // 先 test-fixtures.sql，再 ticket-fixtures.sql
        DatabasePopulatorUtils.execute(new ResourceDatabasePopulator(
                new ClassPathResource("seed/test-fixtures.sql"),
                new ClassPathResource("seed/ticket-fixtures.sql")), jdbc.getDataSource());
    }

    @Test void a_dml_ticket_without_rollback_sql_is_refused() {
        assertThat(postTicket(DEV_TOKEN, body("update t_order set a=1 where id=1", null)).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void a_ddl_ticket_without_a_rollback_plan_is_refused() {
        assertThat(postTicket(DEV_TOKEN, body("create table t_new (id int)", null, null)).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void drop_database_is_blocked_at_submit_and_no_ticket_is_created() {
        assertThat(postTicket(DEV_TOKEN, body("drop database order_db", "重建")).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("select count(*) from ticket", Integer.class)).isZero();
    }

    @Test void a_structural_drop_creates_an_l3_ticket() throws Exception {
        ResponseEntity<String> r = postTicket(DEV_TOKEN, body("drop table t_order", "将从快照重建表结构"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = objectMapper.readTree(r.getBody()).path("id").asLong();
        assertThat(jdbc.queryForObject("select risk_level from ticket where id = ?", String.class, id))
            .isEqualTo("L3");
        assertThat(jdbc.queryForObject("select status from ticket where id = ?", String.class, id))
            .isEqualTo("REVIEWING_L1");
    }

    /** 提交时记录结构指纹：审批后有人改表，执行时才比得出来。 */
    @Test void submitting_records_the_table_fingerprint_from_the_executor() {
        postTicket(DEV_TOKEN, body("update t_order set a=1 where id=1", "update t_order set a=0 where id=1"));
        assertThat(stub.lastFingerprintTables).containsExactly("t_order");
        assertThat(jdbc.queryForObject("select table_fingerprints_json from schema_snapshot", String.class))
            .contains("t_order");
    }

    /** 多语句里最严重的那条决定整单等级（§7.2-1）。 */
    @Test void the_ticket_level_is_the_strictest_of_its_statements() {
        ResponseEntity<String> r = postTicket(DEV_TOKEN,
                body("update t_order set a=1 where id=1;\ntruncate table t_order", "从快照重建"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("select risk_level from ticket where id = ?", String.class,
                idOf(r))).isEqualTo("L3");
    }

    @Test void an_operator_cannot_submit_a_developer_ticket() {
        assertThat(postTicket(OPS_TOKEN, body("update t_order set a=1 where id=1", "x")).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** §4.7-6：没有组长的系统，L3 工单提交时就拦下，而不是让它卡在 REVIEWING_L2。 */
    @Test void an_l3_ticket_is_refused_when_the_system_has_no_lead() {
        jdbc.update("delete from sys_system_operator where system_id = 1 and operator_type = 'LEAD'");
        try {
            ResponseEntity<String> r = postTicket(DEV_TOKEN, body("drop table t_order", "将从快照重建表结构"));
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getBody()).contains("组长");
        } finally {
            jdbc.update("insert into sys_system_operator (system_id, user_id, operator_type) values (1, 5, 'LEAD')");
        }
    }

    /** 研发只能对自己可见的数据源提单。 */
    @Test void submitting_against_an_invisible_datasource_is_forbidden() {
        assertThat(postTicket(DEV_TOKEN, bodyFor(999L, "update pay_db.t set a=1", "x")).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
```

三条 `body(...)` 重载的含义：`body(sql, rollbackSql)`、`body(sql, rollbackPlan, rollbackSql)`、`bodyFor(datasourceId, sql, rollbackSql)`；`idOf(ResponseEntity)` 取响应体 `id`。

- [ ] **Step 2: 运行确认失败**

Run: `.\mvnw.cmd -q -pl platform-control -am test "-Dtest=StatementFingerprintTest"` → FAIL；`verify "-Dit.test=TicketSubmitIT"` → FAIL。

- [ ] **Step 3: 实现**

`submit` 的顺序：

1. 角色必须 `DEVELOPER`（§4.2：运维不提交研发工单）→ 否则 403。
2. `visibilityService.assertVisible(submitterId, datasourceId)`。
3. 归属推导（工单必填"系统、模块"，但请求体只给数据源，避免前端传错）：`system_id` 取 `datasource.system_id`；`module_id` 取"该提交人经由哪个模块拿到这个数据源"——
   ```sql
   select g.module_id from user_module_grant g
     join module_datasource md on md.module_id = g.module_id
    where g.user_id = ? and md.datasource_id = ? order by g.module_id limit 1
   ```
   查不到 → 403（说明可见性来自别处，不该在这里提单）；查到多行取最小 `module_id` 并在 `ticket_event` 里记下"可见性来自多个模块，取 module_id=?"。
4. 语句数 1..5000；每条 `classify`，任一 `BLOCK` → `BizException(INVALID_ARGUMENT, hit.message)`。
5. 必填：`title`、`changeReason`、`expectedExecuteAt`；DML（非只读）必须有 `rollbackSql`；DDL 必须有 `rollbackPlan`；`drop table` 额外要求 `rollbackPlan` 含固定确认语 `将从快照重建表结构`。
6. `rollbackSql` 静态检查：`classify(rollbackSql)` 不得 `BLOCK`；其表名必须命中工单语句里出现过的表（从 `Classification.table()` 收集），否则 400 并说明"回滚 SQL 未命中目标表"。多条回滚语句用 `SqlText.splitStatements` 切开逐条校验。
7. 对每个 `Classification.table()` 非空的表，经 `ExecutorClient.fingerprint(onlyReadConn, tables)` 取指纹（`readonlyConnConfig`）；表不存在 → 记为 `"<table>":""` 并算作"前置状态为空"，不报错。
8. `RiskEngine.assess` 逐条评估，整单等级取最严（§7.2-1）；`ticket.risk_snapshot_hash` 用整单的 `RiskAssessment.snapshotHash`，其中 SQL 部分按"全部语句按序拼接"计算。
9. **L3 的终审可用性**（§4.7-6）：若整单等级为 `L3`，`select count(*) from sys_system_operator where system_id = ? and operator_type = 'LEAD'` 必须 > 0，否则 400「该系统未配置运维组长，L3 工单无人终审」。不在提交时拦，工单就会停在 `REVIEWING_L2` 无人可审，且前端无从解释。
10. 落 `ticket`（`status=REVIEWING_L1`）、`ticket_statement`（逐条 `seq_no`、`statement_type`、`risk_level`）、`schema_snapshot`（`change_seq=0`、`snapshot_ref` 置为 `{systemId}/{datasourceId}/{ticketId}/0` 待执行时填充）、`ticket_event(SUBMITTED)`、`audit(TICKET_SUBMITTED)`、`risk_acknowledgement(stage=SUBMIT)`。

**事务边界**：指纹抓取是外部调用，必须放在事务之外（否则慢查询会一直占着行锁）。因此 `TicketService.submit` 外层**不标** `@Transactional`：先抓指纹 → 再把结果传入落库段。落库段抽成 `TicketStore.create(...)` 并标 `@Transactional`，上面第 1–6 步在落库段内重跑一遍（便宜、纯计算），避免"外部调用期间状态已变"的窗口。

`detail`/`list` 的可见性：提交人可见自己的；运维可见本系统的；组长/管理员同运维。`list` 分页 `page`/`size`（默认 1/20，`size` 上限 100）。

- [ ] **Step 4: 运行确认通过**

Run: `.\mvnw.cmd -q -pl platform-control -am verify "-Dit.test=TicketSubmitIT" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfailsafe.failIfNoSpecifiedTests=false"`，再跑 `"-Dtest=StatementFingerprintTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```powershell
git add platform-control/src
git commit -m "feat(ticket): submit change tickets with rollback requirements and structure fingerprints"
```

---

### Task 8: 审批链

**Files:**
- Create: `platform-control/src/main/java/com/badc/sql/control/ticket/TicketReviewMapper.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/ticket/TicketReviewService.java`
- Modify: `platform-control/src/main/java/com/badc/sql/control/ticket/TicketController.java`
- Test: `platform-control/src/test/java/com/badc/sql/control/ticket/TicketReviewIT.java`

**Interfaces:**
- Consumes: `TicketMapper`、`TicketStateMachine`、`OperatorGuard`（复用归属判定）、`AuditService`。
- Produces: `TicketReviewService.approve(long reviewerId, long ticketId, String comment)`、`TicketReviewService.reject(long reviewerId, long ticketId, String comment)`；端点 `POST /api/v1/tickets/{id}/approve`、`POST /api/v1/tickets/{id}/reject`。

- [ ] **Step 1: 写失败测试 — 三件必须被拒的事**

夹具用任务 7 建的 `seed/ticket-fixtures.sql`：`ops02`（4，系统 1 主运维）、`lead01`（5，系统 1 组长）。既有 `ops01` 是系统 **2** 的运维，正好用来验"跨系统不能审"。

```java
@Test void a_l1_ticket_is_approved_by_one_operator_review() {
    long id = reviewingTicket(1L);          // risk_level=L1, status=REVIEWING_L1
    assertThat(approve(OPS02_TOKEN, id).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(status(id)).isEqualTo("APPROVED");
    assertThat(jdbc.queryForObject("select count(*) from ticket_review where ticket_id = ?", Integer.class, id))
        .isEqualTo(1);
}

@Test void an_l3_ticket_needs_the_lead_after_the_operator() {
    long id = reviewingTicket(3L);          // risk_level=L3
    assertThat(approve(OPS02_TOKEN, id).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(status(id)).as("运维审完不是终态").isEqualTo("REVIEWING_L2");

    assertThat(approve(LEAD01_TOKEN, id).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(status(id)).isEqualTo("APPROVED");
    assertThat(jdbc.queryForObject(
            "select count(*) from ticket_review where ticket_id = ? and review_level = 2", Integer.class, id))
        .isEqualTo(1);
}

/** §4.3-2：提交人 ≠ 审核人。研发没有审核权，这一条先被角色闸拦下。 */
@Test void a_developer_cannot_approve_any_ticket() {
    assertThat(approve(DEV_TOKEN, reviewingTicket(1L)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
}

/**
 * 自审闸的独立验证：直接造一张"提交人就是本系统运维"的工单（§4.2 下正常提交流程不会产生，
 * 但回滚工单与切片 3 的运维通道会产生），必须仍然被拒。
 */
@Test void the_submitter_is_refused_even_when_they_are_an_operator_of_that_system() {
    long id = reviewingTicketSubmittedBy(4L);   // ops02 既是系统 1 主运维，又是这张单的提交人
    assertThat(approve(OPS02_TOKEN, id).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(status(id)).isEqualTo("REVIEWING_L1");
}

/** L3 的一级审核按 §7.1「运维一级 + 组长终审」拆开：组长不做一级，只做终审。 */
@Test void a_lead_cannot_be_the_first_level_reviewer_of_an_l3_ticket() {
    long id = reviewingTicket(3L);
    assertThat(approve(LEAD01_TOKEN, id).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(status(id)).isEqualTo("REVIEWING_L1");
}

/**
 * §4.3-2「两级不可同一人」在 L3 上由角色分离结构性保证：一级只收主/备运维、二级只收组长，
 * 而 `sys_user.role` 是单值，同一账号不可能同时是两者。
 *
 * <p>所以这里<b>不</b>写一条"同一人点两次"的用例——它只会因为角色不符而通过，看起来在测同一人规则，
 * 实际什么也没测到。改钉住真正生效的那道闸：一级由主运维签，主运维再去点终审必须被拒。
 * `reviewer_id != 一级审核人` 仍留在代码里作纵深防御（角色模型将来若变成多值就会生效）。
 */
@Test void only_a_lead_can_close_level_two_after_an_operator_signed_level_one() {
    long id = reviewingTicket(3L);
    assertThat(approve(OPS02_TOKEN, id).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(jdbc.queryForObject(
            "select reviewer_id from ticket_review where ticket_id = ? and review_level = 1", Long.class, id))
        .isEqualTo(4L);

    assertThat(approve(OPS02_TOKEN, id).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(status(id)).isEqualTo("REVIEWING_L2");
}

/** L1/L2 是单级审核，组长可以审（§4.2「审核研发工单·一级」对组长为 ✓）。 */
@Test void a_lead_can_approve_an_l1_ticket_in_one_level() {
    long id = reviewingTicket(1L);
    assertThat(approve(LEAD01_TOKEN, id).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(status(id)).isEqualTo("APPROVED");
}

/** 只有本系统的运维能审：ops01 是系统 2 的运维，审系统 1 的单 → 403。 */
@Test void an_operator_of_another_system_cannot_review() {
    long id = reviewingTicket(1L);
    assertThat(approve(OPS01_TOKEN, id).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(status(id)).as("被拒的审批不能改状态").isEqualTo("REVIEWING_L1");
}

/** 管理员只能配置与授权，不参与审批（§4.2）。 */
@Test void an_admin_cannot_review() {
    assertThat(approve(ADMIN_TOKEN, reviewingTicket(1L)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
}

@Test void approving_a_rejected_ticket_is_a_conflict_not_a_silent_noop() {
    long id = reviewingTicket(1L);
    reject(OPS02_TOKEN, id, "变更说明不清");
    assertThat(approve(OPS02_TOKEN, id).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
}

@Test void a_rejection_requires_a_comment() {
    assertThat(reject(OPS02_TOKEN, reviewingTicket(1L), "").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\mvnw.cmd -q -pl platform-control -am verify "-Dit.test=TicketReviewIT" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfailsafe.failIfNoSpecifiedTests=false"`
Expected: FAIL — 端点不存在。

- [ ] **Step 3: 实现**

`approve(reviewerId, ticketId, comment)`：

1. 读 ticket（不存在 → 404）；`status` 必须是 `REVIEWING_L1` 或 `REVIEWING_L2`，否则 `CONFLICT`。
2. `reviewerId != submitter_id`，否则 403。
3. 归属：`sys_system_operator` 里有 `(system_id, reviewerId)`；`ADMIN` 不可审（§4.2 管理员无审核权）。
4. `REVIEWING_L1`：
   - 若 `risk_level == L3` → 一级审核人的角色必须是 `PRIMARY_OPS` 或 `BACKUP_OPS`（**组长不做 L3 的一级**，§7.1「运维一级 + 组长终审」），写 `ticket_review(review_level=1)`，状态 → `REVIEWING_L2`；
   - 否则 → 角色是 `PRIMARY_OPS`/`BACKUP_OPS`/`OPS_LEAD` 任一即可，写 `review_level=1`，状态 → `APPROVED`（`approved_at`）。
5. `REVIEWING_L2`：角色必须是 **`OPS_LEAD`**，且 `reviewer_id !=` 一级审核人（角色已天然分开，这条是纵深防御）；写 `review_level=2`，状态 → `APPROVED`。
6. 每次审核写 `ticket_event(APPROVED_L1|APPROVED_L2)`、`audit(TICKET_APPROVED, level)`、`risk_acknowledgement(stage=REVIEW|L3_REVIEW)`。

`reject` 同权限判定，状态 → `REJECTED`，`comment` 必填（≥1 字），写 `ticket_event(REJECTED)` 与审计。

状态迁移一律经 `TicketStateMachine.assertTransition`；用 `update ticket set status = ? where id = ? and status = ?`（乐观并发）并在影响行数为 0 时抛 `CONFLICT`——两个组长同时终审时只能有一个生效。

- [ ] **Step 4: 运行确认通过**

Run: `.\mvnw.cmd -q -pl platform-control -am verify "-Dit.test=TicketReviewIT" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfailsafe.failIfNoSpecifiedTests=false"`
Expected: PASS

- [ ] **Step 5: 提交**

```powershell
git add platform-control/src
git commit -m "feat(ticket): two-level approval chain with no self-review and no same-person double review"
```

---

### Task 9: 风险确认落库与执行前校验

**Files:**
- Create: `platform-control/src/main/java/com/badc/sql/control/ticket/RiskAckMapper.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/ticket/RiskAckService.java`
- Modify: `platform-control/src/main/java/com/badc/sql/control/ticket/TicketController.java`（加 `POST /{id}/risk-ack`）
- Test: `platform-control/src/test/java/com/badc/sql/control/ticket/RiskAckIT.java`

**Interfaces:**
- Consumes: `RiskAckMapper`、`TicketMapper`。
- Produces: `RiskAckService.acknowledge(long userId, long ticketId, AckStage stage, String riskSnapshotHash, String ip, String userAgent)`、`RiskAckService.assertExecuteAckCurrent(long ticketId, String currentHash)`；`enum AckStage { SUBMIT, REVIEW, L3_REVIEW, EXECUTE }`。

- [ ] **Step 1: 写失败测试**

```java
@Test void acknowledging_at_the_execute_stage_is_recorded_with_ip_and_ua() { /* 行存在，字段齐全 */ }

/** 风险变了（改了语句或等级变了）就必须重新确认，否则"弹框关掉就没证据"。 */
@Test void an_acknowledgement_for_an_older_risk_hash_does_not_authorise_execution() {
    ack(EXECUTE, "stale-hash");
    assertThatThrownBy(() -> service.assertExecuteAckCurrent(ticketId, "current-hash"))
        .isInstanceOf(BizException.class).hasMessageContaining("风险");
}

@Test void the_latest_acknowledgement_wins() { /* 旧 hash 的 EXECUTE ack 之后又有一条新 hash 的 */ }

@Test void a_submit_stage_acknowledgement_does_not_authorise_execution() { /* 只有 EXECUTE 阶段算 */ }
```

- [ ] **Step 2: 运行确认失败**

Run: `.\mvnw.cmd -q -pl platform-control -am verify "-Dit.test=RiskAckIT" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfailsafe.failIfNoSpecifiedTests=false"`
Expected: FAIL。

- [ ] **Step 3: 实现**

`acknowledge` 插入一行（不做幂等——重复确认本身是证据，保留全部）。`assertExecuteAckCurrent` 取该工单 `stage=EXECUTE` 的**最新**一行（按 `id desc`），`hash` 不等 → `BizException(FORBIDDEN, "风险信息已变更，请重新确认后再执行")`；无行 → 同样拒绝，消息为"执行前必须完成风险确认"。

`ip` / `user_agent` 由控制器从 `HttpServletRequest` 取（`getRemoteAddr()`、`getHeader("User-Agent")`），截断到列宽。

- [ ] **Step 4: 运行确认通过**

Run: `.\mvnw.cmd -q -pl platform-control -am verify "-Dit.test=RiskAckIT" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfailsafe.failIfNoSpecifiedTests=false"`
Expected: PASS

- [ ] **Step 5: 提交**

```powershell
git add platform-control/src
git commit -m "feat(ticket): persist risk acknowledgements and verify the current risk hash before execution"
```

---

### Task 10: 执行器写入执行

**Files:**
- Create: `platform-common/src/main/java/com/badc/sql/common/dto/ExecAccountType.java`
- Create: `platform-common/src/main/java/com/badc/sql/common/dto/ExecuteResult.java`
- Create: `platform-executor/src/main/java/com/badc/sql/executor/exec/WriteRunner.java`
- Modify: `platform-executor/src/main/java/com/badc/sql/executor/api/ExecutorController.java`
- Test: `platform-executor/src/test/java/com/badc/sql/executor/exec/WriteRunnerIT.java`

**Interfaces:**
- Consumes: `MySqlAdapter.url`。
- Produces: `enum ExecAccountType { DML, DDL }`；`record ExecuteResult(boolean success, long affectedRows, long elapsedMs, String errorCode, String errorMessage)`；`WriteRunner.run(ConnConfig conn, String sql, ExecAccountType type) -> ExecuteResult`；端点 `POST /internal/v1/execute`。

- [ ] **Step 1: 写失败测试**

同上：纯 JUnit、固定 `badc_test_writerunner` schema、裸 JDBC。

```java
class WriteRunnerIT {
    private static final String SCHEMA = "badc_test_writerunner";
    private static final String USER = "root";
    private static final String PASS = "root";

    private final WriteRunner runner = new WriteRunner();
    private static ConnConfig conn;

    @BeforeAll static void createSchema() throws Exception { /* 同 SchemaSnapshotterIT：create database */ }

    @AfterAll static void dropSchema() throws Exception { /* drop database if exists */ }

    @Test void an_update_reports_the_affected_row_count() throws Exception {
        execute("create table t (id bigint primary key, a int)");
        execute("insert into t values (1, 0), (2, 0)");

        ExecuteResult r = runner.run(conn, "update t set a = 1", ExecAccountType.DML);

        assertThat(r.success()).isTrue();
        assertThat(r.affectedRows()).isEqualTo(2);
    }

    /** 语句本身有错不是"执行器挂了"：要回结构化错误，让工单能逐条记录失败原因。 */
    @Test void a_failing_statement_returns_a_structured_failure_instead_of_throwing() {
        ExecuteResult r = runner.run(conn, "update no_such_table set a = 1", ExecAccountType.DML);

        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("no_such_table");
        assertThat(r.errorCode()).isNotBlank();
    }

    @Test void a_ddl_statement_succeeds() {
        assertThat(runner.run(conn, "create table t2 (id int)", ExecAccountType.DDL).success()).isTrue();
    }

    /** 每条语句独立提交：前一条成功、后一条失败，前一条不能跟着回滚——这正是"部分失败"的来源。 */
    @Test void each_statement_is_its_own_transaction() throws Exception {
        execute("create table t3 (id int)");

        runner.run(conn, "insert into t3 values (1)", ExecAccountType.DML);
        runner.run(conn, "insert into no_such values (1)", ExecAccountType.DML);

        assertThat(queryInt("select count(*) from t3")).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\mvnw.cmd -q -pl platform-executor -am verify "-Dit.test=WriteRunnerIT" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfailsafe.failIfNoSpecifiedTests=false"`
Expected: FAIL。

- [ ] **Step 3: 实现**

`WriteRunner.run`：`DriverManager.getConnection` → `connection.setAutoCommit(true)` → `statement.setQueryTimeout(type == DDL ? 3600 : 300)` → `executeUpdate`。`SQLException` 一律转成 `ExecuteResult(false, 0, elapsed, SQLState, 清洗后的 message)`，**不抛**——执行器抛异常会让控制面丢失"哪条语句失败、为什么"，整单只能记一个 FAILED。

`errorMessage` 只保留 `e.getMessage()`，绝不带连接串/账号。

- [ ] **Step 4: 运行确认通过**

Run: `.\mvnw.cmd -q -pl platform-executor -am verify "-Dit.test=WriteRunnerIT" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfailsafe.failIfNoSpecifiedTests=false"`
Expected: PASS

- [ ] **Step 5: 提交**

```powershell
git add platform-common/src platform-executor/src
git commit -m "feat(executor): execute write statements per statement with account-specific timeouts"
```

---

### Task 11: 工单执行编排

**Files:**
- Create: `platform-control/src/main/java/com/badc/sql/control/ticket/exec/DatasourceExecLock.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/ticket/exec/TicketExecutionService.java`
- Modify: `platform-control/src/main/java/com/badc/sql/control/ticket/TicketController.java`（加 `POST /{id}/execute`）
- Modify: `platform-control/src/main/java/com/badc/sql/control/query/ExecutorClient.java`（加 `snapshot`、`execute`）
- Modify: `platform-control/src/main/java/com/badc/sql/control/query/HttpExecutorClient.java`
- Modify: `platform-control/src/main/java/com/badc/sql/control/org/ConnAccountService.java`（加 `dmlConnConfig`、`ddlConnConfig`）
- Modify: `platform-control/src/test/java/com/badc/sql/control/query/QueryControllerIT.java:466-536`（桩补两个方法）
- Modify: `platform-control/src/main/java/com/badc/sql/control/ControlApplication.java`（线程池 bean）
- Test: `platform-control/src/test/java/com/badc/sql/control/ticket/exec/DatasourceExecLockIT.java`
- Test: `platform-control/src/test/java/com/badc/sql/control/ticket/exec/TicketExecutionIT.java`

**Interfaces:**
- Consumes: `TicketMapper`、`TicketStatementMapper`、`SchemaSnapshotMapper`、`RiskAckService`、`RiskEngine`、`ExecutorClient.{snapshot,execute}`、`ConnAccountService.{dmlConnConfig,ddlConnConfig}`、`AuditService`。
- Produces:
  - `DatasourceExecLock.acquire(long datasourceId, long ticketId) -> boolean`（插入冲突即 `false`）、`release(long datasourceId, long ticketId)`、`takeOverStale(Duration maxHold) -> int`
  - `TicketExecutionService.requestExecution(long userId, long ticketId)` → 202 语义：取锁、置 `EXECUTING`、提交线程池
  - `ExecutorClient.snapshot(ConnConfig conn, List<String> tables, String relativeRef) -> SnapshotResult`
  - `ExecutorClient.execute(ConnConfig conn, String sql, ExecAccountType type) -> ExecuteResult`
  - `ConnAccountService.dmlConnConfig(long datasourceId) -> ConnConfig`、`ddlConnConfig(long datasourceId) -> ConnConfig`
  - `POST /api/v1/tickets/{id}/execute` → 202；中止原因写入 `ticket_event(event_type='EXEC_ABORTED', detail_json={"reason":"<CODE>"})`，`CODE ∈ {RISK_ESCALATED, RISK_ACK_MISSING, STATEMENT_TAMPERED, SCHEMA_DRIFT, SNAPSHOT_FAILED}`

- [ ] **Step 1: 写失败测试 — 并发、篡改、漂移**

测试统一走 `requestExecution`（真实路径），再用 `awaitTerminal(id)` 轮询状态到终态，避免"提交后立刻断言"的竞态：

```java
private String awaitTerminal(long id) {
    for (int i = 0; i < 200; i++) {
        String s = status(id);
        if (!"EXECUTING".equals(s) && !"APPROVED".equals(s)) return s;
        try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    throw new AssertionError("工单 " + id + " 未在 10s 内进入终态，当前=" + status(id));
}

private String abortReason(long id) {
    return jdbc.queryForObject(
            "select detail_json from ticket_event where ticket_id = ? and event_type = 'EXEC_ABORTED'", String.class, id);
}
```

```java
class DatasourceExecLockIT extends AbstractMySqlIT {
    @Test void only_one_ticket_can_hold_a_datasource() {
        assertThat(lock.acquire(101, 1)).isTrue();
        assertThat(lock.acquire(101, 2)).isFalse();
        lock.release(101, 1);
        assertThat(lock.acquire(101, 2)).isTrue();
    }

    /** 实例崩了不会释放锁：超时后必须能被接管，否则该库永久不可写。 */
    @Test void a_stale_lock_can_be_taken_over_after_the_hold_deadline() {
        lock.acquire(101, 1);
        jdbc.update("update datasource_exec_lock set acquired_at = ? where datasource_id = 101",
                utcMinusHours(3));
        assertThat(lock.takeOverStale(Duration.ofHours(2))).isEqualTo(1);
        assertThat(lock.acquire(101, 2)).isTrue();
    }

    @Test void only_the_holder_can_release() {
        lock.acquire(101, 1);
        lock.release(101, 999);
        assertThat(jdbc.queryForObject("select count(*) from datasource_exec_lock where datasource_id = 101",
                Integer.class)).isEqualTo(1);
    }
}
```

```java
class TicketExecutionIT extends AbstractMySqlIT {
    @Test void an_approved_ticket_executes_and_records_rows_per_statement() {
        long id = approvedTicket("update t_order set a = 1 where id = 1");
        assertThat(requestExecution(DEV_TOKEN, id).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(awaitTerminal(id)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject(
                "select status from ticket_statement where ticket_id = ? order by seq_no limit 1", String.class, id))
            .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject(
                "select affected_rows from ticket_statement where ticket_id = ? order by seq_no limit 1", Long.class, id))
            .isEqualTo(2L);
    }

    @Test void a_failing_second_statement_leaves_the_first_committed_and_marks_partial() {
        long id = approvedTicket("update t_order set a = 1 where id = 1", "update no_such_table set a = 1");
        requestExecution(DEV_TOKEN, id);
        assertThat(awaitTerminal(id)).isEqualTo("PARTIAL_FAILED");
        assertThat(statementStatuses(id)).containsExactly("SUCCEEDED", "FAILED");
    }

    @Test void the_remaining_statements_are_skipped_after_a_failure() {
        long id = approvedTicket("update no_such_table set a = 1", "update t_order set a = 1 where id = 1");
        requestExecution(DEV_TOKEN, id);
        assertThat(awaitTerminal(id)).isEqualTo("FAILED");
        assertThat(statementStatuses(id)).containsExactly("FAILED", "SKIPPED");
    }

    @Test void a_ticket_that_is_not_approved_cannot_be_executed() {
        assertThat(requestExecution(DEV_TOKEN, reviewingTicket(1L)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void another_developers_ticket_cannot_be_executed() {
        long id = approvedTicketSubmittedBy(2L, "update t_order set a = 1 where id = 1");
        assertThat(requestExecution(DEV_TOKEN, id).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(id)).as("被拒之后不能进入 EXECUTING").isEqualTo("APPROVED");
    }

    /** 审批后被改的语句：一条都不许发出去。 */
    @Test void an_approved_ticket_whose_statements_were_edited_is_refused() {
        long id = approvedTicket("update t_order set a = 1 where id = 1");
        jdbc.update("update ticket_statement set sql_text = 'update t_order set a = 2 where id = 1' where ticket_id = ?", id);

        requestExecution(DEV_TOKEN, id);

        assertThat(awaitTerminal(id)).isEqualTo("FAILED");
        assertThat(abortReason(id)).contains("STATEMENT_TAMPERED");
        assertThat(stub.executeCalls).isZero();
    }

    /** 风险确认缺失 → 拒绝执行。 */
    @Test void execution_requires_a_current_risk_acknowledgement() {
        long id = approvedTicketWithoutExecuteAck("update t_order set a = 1 where id = 1");
        requestExecution(DEV_TOKEN, id);
        assertThat(awaitTerminal(id)).isEqualTo("FAILED");
        assertThat(abortReason(id)).contains("RISK_ACK_MISSING");
    }

    /** 表在审批后被加列：漂移必须命中，且 detail 要说清是哪张表。 */
    @Test void a_schema_drift_after_approval_aborts_before_the_first_statement() {
        long id = approvedTicket("update t_order set a = 1 where id = 1");
        stub.fingerprints = Map.of("t_order", "changed-after-approval");

        requestExecution(DEV_TOKEN, id);

        assertThat(awaitTerminal(id)).isEqualTo("FAILED");
        assertThat(abortReason(id)).contains("SCHEMA_DRIFT").contains("t_order");
        assertThat(stub.executeCalls).isZero();
    }

    /** drop table 时表已不存在：不是漂移，按"前置状态为空"继续执行。 */
    @Test void a_missing_table_is_empty_before_state_not_a_drift() {
        long id = approvedTicket("drop table no_such_table");
        stub.fingerprints = Map.of();

        requestExecution(DEV_TOKEN, id);

        assertThat(awaitTerminal(id)).isEqualTo("SUCCEEDED");
        assertThat(abortReason(id)).isNull();
    }

    /** 快照抓取失败 → 一条语句都不执行（Global Constraints）。 */
    @Test void a_failed_snapshot_prevents_every_statement() {
        long id = approvedTicket("update t_order set a = 1 where id = 1");
        stub.snapshotThrows = true;

        requestExecution(DEV_TOKEN, id);

        assertThat(awaitTerminal(id)).isEqualTo("FAILED");
        assertThat(abortReason(id)).contains("SNAPSHOT_FAILED");
        assertThat(stub.executeCalls).isZero();
    }

    /** 拿不到锁时不能把工单改成 EXECUTING，也不能留下语句状态。 */
    @Test void a_second_ticket_on_the_same_datasource_is_refused_without_side_effects() {
        long second = approvedTicket("update t_order set a = 2 where id = 2");
        assertThat(lock.acquire(101, -1L)).as("先占住该库").isTrue();   // -1：不属于任何工单的占位者

        assertThat(requestExecution(DEV_TOKEN, second).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(status(second)).isEqualTo("APPROVED");
        assertThat(statementStatuses(second)).as("失败方不得留下任何执行痕迹").containsOnly("PENDING");
    }
}
```

桩新增字段：`int executeCalls`、`Map<String, String> fingerprints`（默认返回提交时写入的同一组指纹）、`boolean snapshotThrows`、`Set<Integer> failOnStatementIndex`。提交与执行两次取指纹若返回同一 map，漂移检测自然通过——**默认值必须让正常路径通过**，否则每个用例都要手工铺桩。

- [ ] **Step 2: 运行确认失败**

Run: `.\mvnw.cmd -q -pl platform-control -am verify "-Dit.test=DatasourceExecLockIT,TicketExecutionIT" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfailsafe.failIfNoSpecifiedTests=false"`
Expected: FAIL。

- [ ] **Step 3: 实现**

`DatasourceExecLock` 直接 `insert into datasource_exec_lock` 并捕获 `DuplicateKeyException`（**不要**先 select 再 insert：两个请求会同时看到"没有锁"）。`release(datasourceId, ticketId)` 条件里带 `ticket_id = ?`，否则会替别人解锁。

`TicketExecutionService.requestExecution`（同步部分，必须短）：

1. 读工单（不存在 → 404）；`status != APPROVED` → `CONFLICT`。可见性：`submitter_id == userId`，或该用户在本系统 `sys_system_operator` 里 → 允许；否则 403。管理员一律 403（§4.3-5）。
2. **先取锁**：`acquire(datasourceId, ticketId)` 失败 → `CONFLICT("该数据源有工单正在执行，请稍后重试")`。取锁在任何状态变更之前，保证失败方零副作用。
3. `update ticket set status='EXECUTING', executed_at=? where id=? and status='APPROVED'`（乐观），影响 0 行 → 释放锁 + `CONFLICT`。
4. 提交到线程池（见下），返回 202。任务体内 `try { executeNow(ticketId, operatorId) } finally { lock.release(datasourceId, ticketId) }`。

线程池：在 `ControlApplication` 注册 `@Bean("ticketExecutor") ThreadPoolTaskExecutor`，core 2 / max 4 / queue 100 / 拒绝策略 `CallerRunsPolicy`（宁可让请求线程跑，也不要静默丢任务导致工单永久停在 `EXECUTING`）。

`executeNow(ticketId, operatorId)` 的每一步，失败都调同一个 `abort(ticketId, code, detail)`：

```java
private void abort(long ticketId, String code, Map<String, Object> detail) {
    jdbc.update("update ticket set status='FAILED', finished_at=? where id=?", now(), ticketId);
    eventMapper.append(ticketId, "EXEC_ABORTED", operatorId, MAPPER.writeValueAsString(Map.of("reason", code, "detail", detail)));
    auditService.record(new AuditCommand("TICKET_ABORTED", operatorId, null, datasourceId, code, String.valueOf(ticketId), detailJson));
}
```

顺序：

1. 重算风险（`RiskEngine.assess` 逐条，带当前 `EXPLAIN` 估算与窗口）→ 整单等级 > 提交时等级 → `abort(…, "RISK_ESCALATED", {from, to})`。§7.2-6 要求"回退审批"，本切片简化为中止并让用户重新提单（已记入「明确不做」）。
2. `riskAckService.assertExecuteAckCurrent(ticketId, 当前 snapshotHash)`；`BizException` → `abort(…, "RISK_ACK_MISSING", {expected, actual})`。
3. 语句指纹：`StatementFingerprint.of(datasourceId, 当前语句文本)` != `ticket.statement_fingerprint` → `abort(…, "STATEMENT_TAMPERED", {})`。
4. 结构漂移：`ExecutorClient.fingerprint(execConn, tables)` 与 `schema_snapshot(change_seq=0)` 的 `table_fingerprints_json` 逐表比对；不一致 → `abort(…, "SCHEMA_DRIFT", {table: {before, now}})`。**比对规则**：提交时值为空串的表（前置状态为空）不参与比对；执行时返回空 map 而提交时也空 → 通过。
5. 抓执行前快照：`ExecutorClient.snapshot(execConn, tables, "{systemId}/{datasourceId}/{ticketId}/1")`；抛异常 → `abort(…, "SNAPSHOT_FAILED", {})`，**一条语句都不执行**。成功后写 `schema_snapshot(change_seq=1, snapshot_ref, table_fingerprints_json)`。
6. 逐条执行：每条先 `update ticket_statement set status='RUNNING', executed_at=? where ticket_id=? and seq_no=?`，再 `ExecutorClient.execute(conn, sql, accountType)`。`accountType` 由该语句的 `DdlAction` 决定：`NONE` 且 `writeStatement` → `DML`；其余 DDL → `DDL`。连接分别取 `dmlConnConfig` / `ddlConnConfig`。
7. 成功 → `SUCCEEDED` + `affected_rows` + `elapsed_ms`；失败 → `FAILED` + `error_message`，其余未执行语句置 `SKIPPED`，**停止**（不继续跑后面的语句）。
8. 汇总：`SUCCEEDED` 数 == 总数 → `SUCCEEDED`；至少一条 `SUCCEEDED` → `PARTIAL_FAILED`；一条都没成功 → `FAILED`。写 `finished_at`、`ticket_event(FINISHED)`、审计 `TICKET_EXECUTED`：`operator_id` = 点执行的人，`exec_identity` = 实际使用的数据库账号名（`conn.username()`），载荷含 `{succeeded, failed, skipped}`。**这是 §FR-08「审计同时记录研发与执行账号」的落点**——两者必须是不同的两列，不能合并。

`ConnAccountService` 新增 `dmlConnConfig(long)` / `ddlConnConfig(long)`：与 `readonlyConnConfig` 同构，账号缺失 → `NOT_FOUND("数据源 N 未配置 dml 账号")`。

`TicketStubExecutor` 本任务补两个方法：`snapshot(...)` 记 `snapshotRefs` 并在 `snapshotThrows` 为真时抛异常；`execute(...)` 累加 `executeCalls`、按 `failOnStatementIndex` 决定成败并返回固定 `ExecuteResult`。`HttpExecutorClient` 补 `POST /internal/v1/snapshot`、`POST /internal/v1/execute`。

`DatasourceExecLock` 是普通类（构造器吃 `JdbcTemplate`），`DatasourceExecLockIT` 直接 `new DatasourceExecLock(jdbc)`，不必起 Spring 上下文；`TicketExecutionIT` 则要仿 `QueryControllerIT` 用 `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@Import(TicketStubExecutorConfig.class)`，因为要走真实 HTTP 与事务。

`TicketController` 的 `POST /{id}/execute` 返回 `ResponseEntity.accepted()`，正文 `{id, status:"EXECUTING"}`；`POST /{id}/rollback` 由任务 12 加。

`ConnAccountService` 新增 `dmlConnConfig(long)` / `ddlConnConfig(long)`：与 `readonlyConnConfig` 同构，账号缺失 → `NOT_FOUND("未配置 dml 账号")`。

- [ ] **Step 4: 运行确认通过**

Run: `.\mvnw.cmd -q -pl platform-control -am verify "-Dit.test=DatasourceExecLockIT,TicketExecutionIT" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfailsafe.failIfNoSpecifiedTests=false"`
Expected: PASS

- [ ] **Step 5: 提交**

```powershell
git add platform-control/src
git commit -m "feat(ticket): execute approved tickets asynchronously with drift, fingerprint and concurrency guards"
```

---

### Task 12: 回滚工单

**Files:**
- Create: `platform-control/src/main/java/com/badc/sql/control/ticket/TicketRollbackService.java`
- Modify: `platform-control/src/main/java/com/badc/sql/control/ticket/TicketController.java`（加 `POST /{id}/rollback`）
- Test: `platform-control/src/test/java/com/badc/sql/control/ticket/TicketRollbackIT.java`

**Interfaces:**
- Consumes: `TicketMapper`、`TicketStore`（任务 7 的落库段）、`TicketStateMachine`、`OperatorGuard`、`SqlText.splitStatements`。
- Produces: `TicketRollbackService.rollback(long operatorId, long originTicketId, String reason, String suppliedRollbackSqlOrNull) -> long rollbackTicketId`；端点 `POST /api/v1/tickets/{id}/rollback`，请求体 `{reason, rollbackSql?}`。

- [ ] **Step 1: 写失败测试**

```java
@Test void a_rollback_ticket_prefills_the_original_rollback_sql() {
    long origin = succeededTicket("update t_order set a = 1 where id = 1", "update t_order set a = 0 where id = 1");
    long id = rollbackOf(origin, "数据写错了，需要还原");

    assertThat(jdbc.queryForObject("select ticket_type from ticket where id = ?", String.class, id))
        .isEqualTo("ROLLBACK");
    assertThat(jdbc.queryForObject("select origin_ticket_id from ticket where id = ?", Long.class, id))
        .isEqualTo(origin);
    assertThat(statementsOf(id)).containsExactly("update t_order set a = 0 where id = 1");
    assertThat(jdbc.queryForObject("select status from ticket where id = ?", String.class, id))
        .as("回滚也要走审批，不能直接执行").isEqualTo("REVIEWING_L1");
}

@Test void a_rollback_reason_shorter_than_ten_characters_is_refused() {
    long origin = succeededTicket();
    assertThat(rollback(origin, "回滚一下").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(jdbc.queryForObject("select count(*) from ticket where ticket_type = 'ROLLBACK'", Integer.class))
        .isZero();
}

/** 原单为 L3，回滚同样要组长终审（§FR-15）。 */
@Test void a_rollback_of_an_l3_ticket_requires_the_lead() {
    long origin = succeededTicketWithLevel("L3");
    long id = rollbackOf(origin, "结构性变更回退，需要重建表结构");

    approve(OPS02_TOKEN, id);
    assertThat(status(id)).as("L3 回滚一级审完不能终态").isEqualTo("REVIEWING_L2");
    approve(LEAD01_TOKEN, id);
    assertThat(status(id)).isEqualTo("APPROVED");
}

@Test void completing_a_rollback_marks_the_origin_as_rolled_back() {
    long origin = succeededTicket("update t_order set a = 1 where id = 1", "update t_order set a = 0 where id = 1");
    long id = rollbackOf(origin, "数据写错了，需要还原");
    approve(OPS02_TOKEN, id);

    requestExecution(OPS02_TOKEN, id);

    assertThat(awaitTerminal(id)).isEqualTo("SUCCEEDED");
    assertThat(status(origin)).isEqualTo("ROLLED_BACK");
}

/** 原单没有回滚 SQL（DDL 只有文本方案）时不能凭空回滚，必须由发起人给出。 */
@Test void a_ticket_without_rollback_sql_cannot_be_rolled_back_unless_the_operator_supplies_it() {
    long ddlish = succeededTicket("create table t_new (id int)", null, "drop table t_new");

    assertThat(rollback(ddlish, "新表不需要了，删掉它", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    long id = rollbackOf(ddlish, "新表不需要了，删掉它", "drop table t_new");
    assertThat(statementsOf(id)).as("运维补写的 SQL 就是回滚语句").containsExactly("drop table t_new");
}

/** 回滚是运维的动作：研发不能替别人的变更发起回滚。 */
@Test void a_developer_cannot_initiate_a_rollback() {
    long origin = succeededTicket();
    assertThat(rollbackAs(DEV_TOKEN, origin, "数据写错了，需要还原", null).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
}

/** 不是本系统的运维也不行。 */
@Test void an_operator_of_another_system_cannot_rollback() {
    assertThat(rollbackAs(OPS01_TOKEN, succeededTicket(), "数据写错了，需要还原", null).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
}

@Test void a_ticket_that_has_not_run_cannot_be_rolled_back() {
    assertThat(rollback(reviewingTicket(1L), "还没跑过就回滚", null).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
}
```

助手签名（都返回 `ResponseEntity<String>`，除非注明）：

- `rollback(long originId, String reason)` / `rollback(long originId, String reason, String suppliedSql)` —— 以 `OPS02_TOKEN` 发起；
- `rollbackAs(String token, long originId, String reason, String suppliedSql)`；
- `rollbackOf(...)` —— 同上但断言 201 并返回新工单 id；
- `succeededTicket()` / `succeededTicket(String sql, String rollbackSql)` / `succeededTicket(String sql, String rollbackPlan, String rollbackSql)` / `succeededTicketWithLevel(String level)` —— 直接插一张 `SUCCEEDED` 的原单（不跑真执行）；
- `statementsOf(ticketId)` —— 按 `seq_no` 取 `sql_text` 列表。

- [ ] **Step 2: 运行确认失败**

Run: `.\mvnw.cmd -q -pl platform-control -am verify "-Dit.test=TicketRollbackIT" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfailsafe.failIfNoSpecifiedTests=false"`
Expected: FAIL。

- [ ] **Step 3: 实现**

`rollback` 的规则：

1. **发起人必须是本系统的运维或组长**（§4.2：研发无"发起回滚"能力，回滚是一次新的变更）；管理员 403。归属判定复用 `sys_system_operator(system_id, operatorId)`。
2. 原单必须 `SUCCEEDED` 或 `PARTIAL_FAILED`（其余 → `CONFLICT`，含"还没跑过"）。
3. `reason.trim().length() >= 10`。
4. 回滚语句的来源，按顺序取**第一个非空**：①请求体的 `rollbackSql`（运维可编辑/补写）②原单的 `rollback_sql`（提交时填的，唯一自动来源，系统**不生成**）。两者都空 → 400「原单未填写回滚 SQL，请提供回滚语句」。DDL 原单只有文本方案，因此必然走 ①——这是 `drop table` 唯一可行的回滚路径。
5. 用 `SqlText.splitStatements` 把回滚语句切成多条，走与任务 7 相同的 `BLOCK` 校验、表名命中校验、指纹抓取与 `REVIEWING_L1` 落库；`risk_level` 取"原单等级"与"回滚语句重算等级"中较高者（原单 L3 → 回滚必然 L3，满足 §FR-15）。
6. 新单 `ticket_type='ROLLBACK'`、`origin_ticket_id=原单`、`rollback_reason=reason`、`system_id`/`module_id`/`datasource_id` 继承原单。

原单置 `ROLLED_BACK` 发生在**回滚工单执行成功之后**：任务 11 汇总处，若 `ticket_type == ROLLBACK && status == SUCCEEDED` → 原单 `ROLLED_BACK` + `ticket_event(ROLLED_BACK)`（迁移表里 `SUCCEEDED → ROLLED_BACK` 已允许）。若回滚单 `PARTIAL_FAILED`，原单保持原状态并写 `ticket_event(ROLLBACK_FAILED)`——不能把没还原的数据标成已回滚。

- [ ] **Step 4: 运行确认通过**

Run: `.\mvnw.cmd -q -pl platform-control -am verify "-Dit.test=TicketRollbackIT" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfailsafe.failIfNoSpecifiedTests=false"`
Expected: PASS

- [ ] **Step 5: 提交**

```powershell
git add platform-control/src
git commit -m "feat(ticket): add rollback tickets that inherit the original rollback SQL and risk level"
```

---

### Task 13: 过期、卡死恢复与审计补齐

**Files:**
- Create: `platform-control/src/main/java/com/badc/sql/control/ticket/exec/TicketRecoveryJob.java`
- Modify: `platform-control/src/main/java/com/badc/sql/control/ControlApplication.java`（`@EnableScheduling`）
- Modify: `platform-control/src/main/java/com/badc/sql/control/query/QueryService.java:157-162`（版本拒绝写审计）
- Test: `platform-control/src/test/java/com/badc/sql/control/ticket/exec/TicketRecoveryJobIT.java`
- Test: `platform-control/src/test/java/com/badc/sql/control/query/QueryControllerIT.java`（新增一条审计断言）

**Interfaces:**
- Consumes: `TicketMapper`、`DatasourceExecLock`、`AuditService`、`Clock`。
- Produces: `TicketRecoveryJob.expireApproved()`、`TicketRecoveryJob.recoverStuckExecuting()`、`TicketRecoveryJob.takeOverStaleLocks()`；`@Scheduled(fixedDelayString = "PT5M")` 依次调用三者。

- [ ] **Step 1: 写失败测试**

```java
@Test void an_approved_ticket_older_than_twenty_four_hours_expires() {
    long id = approvedTicket("update t_order set a = 1 where id = 1");
    jdbc.update("update ticket set approved_at = ? where id = ?", utcMinusHours(25), id);

    job.expireApproved();

    assertThat(status(id)).isEqualTo("EXPIRED");
    assertThat(jdbc.queryForObject("select count(*) from ticket_event where ticket_id = ? and event_type = 'EXPIRED'",
            Integer.class, id)).isEqualTo(1);
}

@Test void a_ticket_approved_one_hour_ago_is_left_alone() {
    long id = approvedTicket("update t_order set a = 1 where id = 1");
    jdbc.update("update ticket set approved_at = ? where id = ?", utcMinusHours(1), id);
    job.expireApproved();
    assertThat(status(id)).isEqualTo("APPROVED");
}

/** 实例在执行中途重启：不能永久停在 EXECUTING。 */
@Test void an_executing_ticket_older_than_the_hold_deadline_needs_manual_confirmation() {
    long id = approvedTicket("update t_order set a = 1 where id = 1");
    jdbc.update("update ticket set status='EXECUTING', executed_at=? where id=?", utcMinusHours(3), id);

    job.recoverStuckExecuting();

    assertThat(status(id)).isEqualTo("NEEDS_MANUAL_CONFIRM");
    assertThat(jdbc.queryForObject("select count(*) from datasource_exec_lock where datasource_id = 101",
            Integer.class)).as("恢复后必须释放锁，否则该库永久不可写").isZero();
}

@Test void recovery_does_not_resume_execution_automatically() {
    long id = approvedTicket("update t_order set a = 1 where id = 1");
    jdbc.update("update ticket set status='EXECUTING', executed_at=? where id=?", utcMinusHours(3), id);

    job.recoverStuckExecuting();

    assertThat(stub.executeCalls).as("§11：重启后需人工确认，不自动续跑").isZero();
}

@Test void a_finished_ticket_is_never_touched() {
    long id = approvedTicket("update t_order set a = 1 where id = 1");
    jdbc.update("update ticket set status='SUCCEEDED', approved_at=?, executed_at=? where id=?",
            utcMinusHours(48), utcMinusHours(48), id);
    job.expireApproved();
    job.recoverStuckExecuting();
    assertThat(status(id)).isEqualTo("SUCCEEDED");
}
```

```java
// QueryControllerIT 新增
@Test void a_version_rejection_is_written_to_the_audit_chain() throws Exception {
    stub.validateResult = ValidationResult.reject("DB_VERSION_UNSUPPORTED", "该库为 MySQL 5.7，不支持 WITH 子句");
    post(DEV_TOKEN, "{\"datasourceId\":101,\"sql\":\"with t as (select 1) select * from t\"}");
    assertThat(jdbc.queryForObject(
            "select count(*) from audit_event where event_type = 'QUERY_REJECTED' and action = 'db_version_unsupported'",
            Integer.class)).isEqualTo(1);
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\mvnw.cmd -q -pl platform-control -am verify "-Dit.test=TicketRecoveryJobIT" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfailsafe.failIfNoSpecifiedTests=false"`
Expected: FAIL。

- [ ] **Step 3: 实现**

`expireApproved`：`update ticket set status='EXPIRED' where status='APPROVED' and approved_at < ?`（`clock.instant()` 减 24h），逐行写 `ticket_event(EXPIRED)` 与审计 `TICKET_EXPIRED`。

`recoverStuckExecuting`：`status='EXECUTING' and executed_at < ?`（减 2h）→ `NEEDS_MANUAL_CONFIRM`；同时 `DatasourceExecLock.release` 对应数据源（只有 `datasource_exec_lock.ticket_id` 等于该单时才释放），审计 `TICKET_RECOVERY`，消息里带上"未自动续跑（§11）"。

`takeOverStaleLocks`：`datasource_exec_lock.acquired_at < ?`（减 2h）且对应工单已非 `EXECUTING` → 删除该锁行。

`QueryService.assertVersionSupported` 在抛 422 之前加一条 `auditService.record(new AuditCommand("QUERY_REJECTED", userId, null, datasourceId, "db_version_unsupported", null, "{\"version\":\"...\"}"))`——把 `userId` 传进这个方法（当前签名只有 `datasourceId`、`conn`、`sql`，需要加一个 `Long userId` 参数）。

- [ ] **Step 4: 运行确认通过（整支）**

Run: `.\mvnw.cmd verify`
Expected: BUILD SUCCESS，且集成测试数比切片 1 的 78 增加。

- [ ] **Step 5: 提交**

```powershell
git add platform-control/src
git commit -m "feat(ticket): expire and recover stuck tickets, and audit version rejections"
```

---

## 自检记录

**spec 覆盖**：FR-02 结构快照（任务 5、7、11）、FR-08 变更工单（任务 7、8、11）、FR-09/§7 风险分级（任务 3、4、6）、FR-10 格式校验（任务 3、4；L1 语法校验复用切片 1 的 `MySqlDialect`）、FR-15 回滚工单（任务 12）、§4.3 硬约束（任务 7、8、11）、§5.3 状态机（任务 2）、§7.4 风险确认（任务 9）、§11 异常表（任务 11 的漂移/锁/部分失败，任务 13 的卡死恢复）。未覆盖项已在「明确不做」里逐条登记。

**已知偏差（需在实施前确认）**：

1. **`update`/`delete` 的 L1 依赖 `EXPLAIN` 估算**，而 spec §7.1 的 `≤100 行` 语义是精确值。估算为 `-1` 时一律 L3（保守），因此简单 `update` 在 `EXPLAIN` 不可用时会被升到 L3。这是本切片最明显的偏离，代价是可能的审批疲劳，收益是不引入 SQL 改写器。
2. **`ALTER_MODIFY` 收窄判定**在没有历史列类型输入的前提下用模式匹配（`bigint→int`、`varchar(n)→varchar(m)` 且 `m<n` 等），匹配不到按收窄处理。比 spec 的"改类型收窄"更严。
3. **`UNKNOWN` 归类为 L3**：无法识别的写语句默认最高级，不默认放行。
4. **执行窗口只有全局默认值**，无逐系统配置。
5. **工单执行异步且无重试**：失败即终态，重试需重新提单（spec §11 允许"保持 APPROVED 可重试"，本切片未做成按钮）。
6. **回滚工单的发起人与 SQL 来源**：spec §FR-15 只说"原工单详情页发起回滚、预填原单回滚 SQL、运维可编辑"，未说研发能否发起。本切片限定**只由本系统运维/组长发起**，且当原单没有回滚 SQL（DDL 只有文本方案）时，允许发起人补写——否则 `drop table` 这类最需要回滚的变更永远无法回滚。这把"预填"从"唯一来源"放宽为"可预填、可补写"，需要确认。
7. **L3 的两级按角色结构性分离**：一级只收 `PRIMARY_OPS`/`BACKUP_OPS`，二级只收 `OPS_LEAD`。这比"两人不同"的运行时校验更稳——即使某系统只配了一名组长，也不会出现"组长做了一级、终审无人可做"的死锁。代价是组长不能做 L3 的一级审核（§4.2 写的是 ✓，本切片按 §7.1 的"运维一级 + 组长终审"收窄）。**这条需要你确认**。
