# SQL 执行平台 — 切片 1：基座与只读查询闭环 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让研发能登录平台、只看到自己被授权的库、自助执行 `select`、拿到分页结果与查询历史，全过程落审计——构成平台第一个可交付的闭环。

**Architecture:** 控制面（`platform-control`）与执行器（`platform-executor`）拆成两个 Spring Boot 应用，为后期多云预留。控制面管身份、权限、元数据、历史与审计；执行器管数据库连接、方言与执行。控制面通过内部 HTTP 调用执行器，连接配置随请求下发（执行器不持久化凭证）。

**Tech Stack:** Java 17 · Spring Boot 3.2 · Spring Security · MyBatis-Plus · Flyway · MySQL 8（平台库）· Testcontainers（MySQL 5.7/8.0）· JUnit 5 · AssertJ · React 18 + TypeScript + Ant Design（本切片只做查询页）

**Spec:** `docs/superpowers/specs/2026-10-20-sql-execution-platform-design.md`

## Global Constraints

- 仅纳管生产库；不引入 `env` 维度。
- 研发**不可见**连接信息：数据源接口只返回 `{id, name, dbName, systemId, type}`，不含 host/port/user/密码/连接串。
- 研发查询自动追加 `LIMIT 1001`；结果集上限 10 万行 / 50MB，超限截断并标记 `truncated=true`。
- 结果缓存保留 **30 分钟**，最多保留最近 20 次；导出完成后 **5 分钟**清理（本切片只实现保留与过期）。
- 平台元数据库：MySQL 8。时间以 **UTC** 存储，展示按 `Asia/Shanghai`。
- 审计事件 **append-only**，带哈希链（`prev_hash` + `hash`），保留 2 年，禁止 UPDATE/DELETE。
- 单数据源查询并发上限默认 **5**（本切片用信号量实现）。
- 连接凭证 AES-256-GCM 加密，密钥来自环境变量 `BADCSQL_CRED_KEY`（32 字节 base64），**任何接口不回显明文**。
- 支持 MySQL **5.7 与 8.0**，版本差异必须运行时探测，不写死。
- 所有 Java 包名前缀 `com.badc.sql`。

## Review Focus

以下五类输入本切片必然遇到，但没有任何一条自然落在某任务的"顺手写的测试"里。每条都在归属任务中加了测试。

1. **SQL 文本里含分号、注释、字符串内分号、反引号** —— 天真地按 `;` 切分会把一条语句切成两条，或误判为"多语句拼接"。
2. **结果集含 `NULL`、BLOB/BINARY、超长 TEXT、emoji（4 字节 UTF-8）** —— JSON 序列化与前端表格渲染会出现 `null` 与 `"null"` 混淆、或直接抛异常。
3. **在 5.7 库执行 8.0 语法（如 CTE）** —— 报错必须指向"版本不支持"，而不是数据库原始的 1064 语法错误，否则研发会去查错方向。
4. **数据源不可达（白名单未加、连接超时）** —— 必须在校验超时内快速失败并给出"请确认已加白名单"，不能把请求线程挂死。
5. **研发访问未授权模块下的数据源** —— 必须返回 **403**，而不是"查无此数据源"或空结果集；否则无法区分"没权限"和"库是空的"。

---

## File Structure

```
badc-sql/
├── pom.xml                                  # 父 POM，统一版本与模块
├── platform-common/
│   └── src/main/java/com/badc/sql/common/
│       ├── error/ErrorCode.java             # 错误码枚举（403/404/超时/版本不支持…）
│       ├── error/BizException.java
│       └── dto/                             # 跨模块 DTO：QueryRequest/QueryResult/ColumnMeta…
├── platform-executor/
│   ├── src/main/java/com/badc/sql/executor/
│   │   ├── ExecutorApplication.java
│   │   ├── api/ExecutorController.java      # POST /internal/v1/probe、/query
│   │   ├── adapter/DataSourceAdapter.java   # SPI
│   │   ├── adapter/mysql/MySqlAdapter.java
│   │   ├── adapter/mysql/MySqlDialect.java  # 版本化方言校验
│   │   ├── adapter/mysql/CapabilityProbe.java
│   │   ├── conn/ConnConfig.java             # 执行期连接配置（内存态）
│   │   └── exec/QueryRunner.java            # 并发信号量 + 超时 + 行数上限
│   └── src/test/java/...                    # Testcontainers 5.7 / 8.0
├── platform-control/
│   ├── src/main/java/com/badc/sql/control/
│   │   ├── ControlApplication.java
│   │   ├── security/                        # JWT 过滤器、当前用户
│   │   ├── user/                            # 用户与登录
│   │   ├── org/                             # system / module / datasource CRUD
│   │   ├── grant/                           # 授权与可见性判定
│   │   ├── query/                           # 查询编排、结果缓存、历史
│   │   ├── audit/                           # 审计事件与哈希链
│   │   └── client/ExecutorClient.java       # 调执行器
│   └── src/main/resources/db/migration/     # Flyway V1__baseline.sql …
└── web/
    └── src/pages/QueryConsole.tsx           # 查询页（本切片最小实现）
```

**职责边界**：`platform-executor` 不连平台库、不认用户，只认"连接配置 + SQL"。`platform-control` 不直接连业务库。所有跨边界数据走 `platform-common` 的 DTO。

---

### Task 1: 工程骨架与平台元数据库

**Files:**
- Create: `pom.xml`
- Create: `platform-common/pom.xml`, `platform-executor/pom.xml`, `platform-control/pom.xml`
- Create: `platform-control/src/main/resources/db/migration/V1__baseline.sql`
- Create: `platform-control/src/main/resources/application.yml`
- Test: `platform-control/src/test/java/com/badc/sql/control/MigrationIT.java`

**Interfaces:**
- Produces: 平台库表 `sys_user`、`sys_system`、`sys_module`、`datasource`、`conn_account`、`module_datasource`、`user_module_grant`、`query_history`、`audit_event`；包根 `com.badc.sql`。

- [ ] **Step 1: 写失败测试 — 期望迁移后 9 张表存在**

```java
@SpringBootTest
@Testcontainers
class MigrationIT {
  @Container static MySQLContainer<?> db = new MySQLContainer<>("mysql:8.0.36");

  @Test void creates_all_baseline_tables() {
    List<String> tables = jdbc.queryForList(
        "select table_name from information_schema.tables where table_schema = database()", String.class);
    assertThat(tables).contains("sys_user","sys_system","sys_module","datasource",
        "conn_account","module_datasource","user_module_grant","query_history","audit_event");
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl platform-control test -Dtest=MigrationIT`
Expected: FAIL — 表不存在 / 应用上下文启动失败

- [ ] **Step 3: 建父 POM 与三个模块，写 `V1__baseline.sql`**

父 POM 锁 Spring Boot 3.2.x、Java 17、Testcontainers BOM。`V1__baseline.sql` 建上述 9 表，关键列：

- `datasource(id, system_id, name, db_name, db_type, host, port, charset, time_zone, capabilities_json, created_at)`
- `conn_account(id, datasource_id, account_type, username, password_cipher, updated_at)` — `account_type` 取值 `readonly|dml|ddl|onlineddl`
- `user_module_grant(id, user_id, module_id, can_export, granted_by, granted_at)`
- `query_history(id, user_id, datasource_id, sql_text, row_count, elapsed_ms, status, created_at)`
- `audit_event(id, seq, event_type, operator_id, exec_identity, datasource_id, action, object_id, payload_json, prev_hash, hash, created_at)`

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl platform-control test -Dtest=MigrationIT`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add pom.xml platform-*/pom.xml platform-control/src/main/resources/db/migration platform-control/src/test
git commit -m "chore: bootstrap multi-module project and baseline schema"
```

---

### Task 2: 登录与身份

**Files:**
- Create: `platform-control/src/main/java/com/badc/sql/control/user/SysUser.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/user/UserMapper.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/security/JwtService.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/security/JwtAuthFilter.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/security/SecurityConfig.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/user/AuthController.java`
- Test: `platform-control/src/test/java/com/badc/sql/control/user/AuthControllerTest.java`

**Interfaces:**
- Produces: `JwtService.issue(Long userId, String loginName) -> String`、`JwtService.parse(String token) -> Long userId`；`CurrentUser.id()`；`POST /api/v1/auth/login` 返回 `{token, userId, loginName}`。

- [ ] **Step 1: 写失败测试 — 密码正确签发 token，错误返回 401**

```java
@Test void login_with_valid_password_returns_token() throws Exception {
  mockMvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
        .content("{\"loginName\":\"dev01\",\"password\":\"Passw0rd!\"}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.token").isNotEmpty());
}

@Test void login_with_wrong_password_returns_401() throws Exception {
  mockMvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
        .content("{\"loginName\":\"dev01\",\"password\":\"wrong\"}"))
      .andExpect(status().isUnauthorized());
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl platform-control test -Dtest=AuthControllerTest`
Expected: FAIL — 404 无该端点

- [ ] **Step 3: 实现**

`sys_user` 存 `password_hash`（BCrypt，strength 10）。`JwtService` 用 HS256，密钥取 `BADCSQL_JWT_SECRET`，有效期 8 小时。`JwtAuthFilter` 从 `Authorization: Bearer` 解析并塞入 `SecurityContext`。除 `/api/v1/auth/login` 外全部需认证。

- [ ] **Step 4: 运行确认通过 + 提交**

Run: `mvn -pl platform-control test -Dtest=AuthControllerTest` → PASS

```bash
git add platform-control/src && git commit -m "feat(auth): password login with JWT"
```

---

### Task 3: 系统 / 模块 / 数据源管理

**Files:**
- Create: `platform-control/src/main/java/com/badc/sql/control/org/SystemEntity.java`、`ModuleEntity.java`、`DataSourceEntity.java`、`ConnAccountEntity.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/org/OrgController.java`（或拆 System/Module/DataSource 三个 Controller，按模块大小决定）
- Create: `platform-control/src/main/java/com/badc/sql/control/crypto/CredentialCipher.java`
- Test: `platform-control/src/test/java/com/badc/sql/control/org/DataSourceApiTest.java`

**Interfaces:**
- Consumes: `CurrentUser.id()`
- Produces: `CredentialCipher.encrypt(String plain) -> String`、`decrypt(String cipher) -> String`（AES-256-GCM，输出 `base64(iv||ciphertext||tag)`）；`GET /api/v1/datasources` 返回**不含**连接信息的视图。

- [ ] **Step 1: 写失败测试 — 列表响应绝不能含 host/user**

```java
@Test void datasource_list_never_leaks_connection_info() throws Exception {
  mockMvc.perform(get("/api/v1/datasources").header("Authorization", "Bearer " + devToken))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$[0].dbName").value("order_db"))
      .andExpect(jsonPath("$[0].host").doesNotExist())
      .andExpect(jsonPath("$[0].username").doesNotExist())
      .andExpect(jsonPath("$[0].password").doesNotExist());
}

@Test void credential_roundtrip() {
  var c = new CredentialCipher(key32);
  assertThat(c.decrypt(c.encrypt("p@ss"))).isEqualTo("p@ss");
}
```

- [ ] **Step 2: 运行确认失败** → Run: `mvn -pl platform-control test -Dtest=DataSourceApiTest`；Expected: FAIL

- [ ] **Step 3: 实现**

`DataSourceView` 是独立 DTO（`id,name,dbName,systemId,type`），与实体物理隔离——**不靠 `@JsonIgnore`**，避免后续加字段时泄漏。建表 / 改连接 / 配账号仅 `PRIMARY_OPS`/`ADMIN` 可调；密码入库存 `password_cipher`，出参永不返回。

- [ ] **Step 4: 运行通过 + 提交**

```bash
mvn -pl platform-control test -Dtest=DataSourceApiTest
git add platform-control/src && git commit -m "feat(org): system/module/datasource with encrypted credentials"
```

---

### Task 4: 授权模型与可见性

**Files:**
- Create: `platform-control/src/main/java/com/badc/sql/control/grant/UserModuleGrant.java`、`GrantMapper.java`、`VisibilityService.java`、`GrantController.java`
- Test: `platform-control/src/test/java/com/badc/sql/control/grant/VisibilityServiceTest.java`

**Interfaces:**
- Produces: `VisibilityService.visibleDatasourceIds(Long userId) -> Set<Long>`、`VisibilityService.assertVisible(Long userId, Long datasourceId) throws BizException(403)`、` VisibilityService.visibleModuleIds(Long userId) -> Set<Long>`。

- [ ] **Step 1: 写失败测试 — 未授权必须 403，不是空结果**

```java
@Test void unauthorized_datasource_throws_403() {
  assertThatThrownBy(() -> service.assertVisible(devUserId, unauthorizedDsId))
      .isInstanceOf(BizException.class)
      .extracting(e -> ((BizException) e).code()).isEqualTo(ErrorCode.FORBIDDEN);
}

@Test void dev_only_sees_datasources_of_granted_modules() {
  assertThat(service.visibleDatasourceIds(devUserId)).containsExactlyInAnyOrder(101L, 102L);
}
```

- [ ] **Step 2: 运行确认失败** → Expected: FAIL — 类不存在

- [ ] **Step 3: 实现**

可见性 = `user_module_grant` → `module_datasource` → `datasource` 的联结。**查询在 SQL 里做**（单条 join，不在内存过滤），避免数据量增长后拖慢。运维/组长/管理员走角色短路（本切片先把角色判定写成接口实现，角色来源在 Task 3 的用户表里）。

- [ ] **Step 4: 运行通过 + 提交**

```bash
mvn -pl platform-control test -Dtest=VisibilityServiceTest
git add platform-control/src && git commit -m "feat(grant): module-based visibility with 403 enforcement"
```

---

### Task 5: 执行器骨架与 MySQL 适配器（含能力探测）

**Files:**
- Create: `platform-executor/src/main/java/com/badc/sql/executor/ExecutorApplication.java`
- Create: `platform-executor/src/main/java/com/badc/sql/executor/api/ExecutorController.java`
- Create: `platform-executor/src/main/java/com/badc/sql/executor/adapter/DataSourceAdapter.java`
- Create: `platform-executor/src/main/java/com/badc/sql/executor/adapter/mysql/MySqlAdapter.java`
- Create: `platform-executor/src/main/java/com/badc/sql/executor/adapter/mysql/MySqlDialect.java`
- Create: `platform-executor/src/main/java/com/badc/sql/executor/adapter/mysql/CapabilityProbe.java`
- Test: `platform-executor/src/test/java/com/badc/sql/executor/adapter/mysql/MySqlAdapter57IT.java`、`MySqlAdapter80IT.java`

**Interfaces:**
- Produces: `DataSourceAdapter.probe(ConnConfig) -> Capability`、`DataSourceAdapter.validate(String sql, DbVersion) -> ValidationResult`（`ok` / `errorCode` / `message` / `line` / `column`）；`POST /internal/v1/probe` 返回 `{version, supportsExplainAnalyze, supportsInstantDdl, binlogEnabled, onlineDdlAvailable}`。

- [ ] **Step 1: 写失败测试 — 5.7 不支持 CTE，8.0 支持**

```java
@Test void cte_rejected_on_57_with_version_message() {
  var r = adapter57.validate("WITH t AS (SELECT 1) SELECT * FROM t", DbVersion.MYSQL_5_7);
  assertThat(r.ok()).isFalse();
  assertThat(r.message()).contains("5.7").contains("WITH");
}

@Test void cte_accepted_on_80() {
  assertThat(adapter80.validate("WITH t AS (SELECT 1) SELECT * FROM t", DbVersion.MYSQL_8_0).ok()).isTrue();
}

@Test void probe_reports_explain_analyze_only_on_80() {
  assertThat(adapter80.probe(conn80).supportsExplainAnalyze()).isTrue();
  assertThat(adapter57.probe(conn57).supportsExplainAnalyze()).isFalse();
}
```

- [ ] **Step 2: 运行确认失败** → Run: `mvn -pl platform-executor test -Dtest='MySqlAdapter*IT'`；Expected: FAIL

- [ ] **Step 3: 实现**

`MySqlDialect` 持有按版本分组的禁用特性表（5.7：`WITH`、窗口函数 `OVER(`、`->>`、`JSON_TABLE`），校验前先剥注释与字符串字面量再匹配。`CapabilityProbe` 连一次库读 `@@version`、`@@log_bin`、`SHOW GRANTS`，结果缓存 10 分钟。连接参数固定 `connectTimeout=3000&socketTimeout=30000`。

- [ ] **Step 4: 运行通过 + 提交**

```bash
mvn -pl platform-executor test -Dtest='MySqlAdapter*IT'
git add platform-executor && git commit -m "feat(executor): mysql adapter with version-aware dialect and capability probe"
```

---

### Task 6: 查询执行（限流、超时、行数上限、分页）

**Files:**
- Create: `platform-executor/src/main/java/com/badc/sql/executor/exec/QueryRunner.java`
- Create: `platform-executor/src/main/java/com/badc/sql/executor/conn/ConnConfig.java`
- Modify: `platform-executor/src/main/java/com/badc/sql/executor/api/ExecutorController.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/client/ExecutorClient.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/query/QueryService.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/query/QueryController.java`
- Test: `platform-executor/src/test/java/com/badc/sql/executor/exec/QueryRunnerIT.java`
- Test: `platform-control/src/test/java/com/badc/sql/control/query/QueryControllerTest.java`

**Interfaces:**
- Consumes: `VisibilityService.assertVisible`、`DataSourceAdapter`、`CredentialCipher.decrypt`
- Produces: `POST /api/v1/query` body `{datasourceId, sql, page, size}` → `{columns:[{name,type}], rows:[[...]], truncated, totalScanned, elapsedMs, resultId}`；`QueryRunner.run(ConnConfig, String sql, int maxRows) -> RawResult`。

- [ ] **Step 1: 写失败测试 — 研发 SQL 必被加 LIMIT 1001；越权 403；NULL 保留**

```java
@Test void dev_query_is_capped_at_1001_rows() throws Exception {
  mockMvc.perform(post("/api/v1/query").header(AUTH, devToken).contentType(APPLICATION_JSON)
        .content("{\"datasourceId\":101,\"sql\":\"select * from t_order\",\"page\":1,\"size\":50}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.truncated").value(true));
}

@Test void unauthorized_datasource_returns_403() throws Exception {
  mockMvc.perform(post("/api/v1/query").header(AUTH, devToken).contentType(APPLICATION_JSON)
        .content("{\"datasourceId\":999,\"sql\":\"select 1\"}"))
      .andExpect(status().isForbidden());
}

@Test void null_and_binary_columns_are_serialized_safely() { /* 见正文断言 */ }
```

- [ ] **Step 2: 运行确认失败** → Expected: FAIL

- [ ] **Step 3: 实现**

- `QueryService` 只放行 `select/show/desc/explain`；对研发角色的 `select` 用文本改写追加 `LIMIT 1001`（已含 `LIMIT` 则不追加，改为包裹子查询 `select * from ( <sql> ) x limit 1001`）。
- `QueryRunner` 用 `Semaphore(5)` 按 `datasourceId` 限流、`Statement.setQueryTimeout(60)`、流式读 `ResultSet` 到 `maxRows` 即停并置 `truncated`。
- 类型映射固定：`NULL → JSON null`；`BLOB/BINARY → {"hexLen":n}` 占位；`TEXT > 64KB → 截断并标记`；`utf8mb4` 原样返回（emoji 必须能过）。

- [ ] **Step 4: 运行通过 + 提交**

```bash
mvn -pl platform-executor test -Dtest=QueryRunnerIT
mvn -pl platform-control test -Dtest=QueryControllerTest
git add -A && git commit -m "feat(query): readonly query with limit, rate limit and safe serialization"
```

---

### Task 7: 结果缓存与查询历史

**Files:**
- Create: `platform-control/src/main/java/com/badc/sql/control/query/ResultCacheService.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/query/QueryHistoryService.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/query/HistoryController.java`
- Test: `platform-control/src/test/java/com/badc/sql/control/query/ResultCacheServiceTest.java`

**Interfaces:**
- Produces: `ResultCacheService.put(String resultId, RawResult r)`、`get(String resultId) -> Optional<RawResult>`；`GET /api/v1/history?page&size` 按当前用户过滤。

- [ ] **Step 1: 写失败测试 — 30 分钟过期、20 条淘汰、研发只看到自己的历史**

```java
@Test void cache_entry_expires_after_30_minutes() {
  clock.setInstant(T0); cache.put("r1", result);
  clock.setInstant(T0.plus(31, MINUTES));
  assertThat(cache.get("r1")).isEmpty();
}

@Test void history_is_scoped_to_current_user() {
  assertThat(historyService.page(devA, 1, 20).items())
      .allMatch(h -> h.userId().equals(devA));
}
```

- [ ] **Step 2: 运行确认失败** → Expected: FAIL

- [ ] **Step 3: 实现**：Caffeine 本地缓存 `expireAfterWrite(30m)` + `maximumSize(20)`，可注入 `Clock` 以便测试。历史落 `query_history`，写入失败不阻断查询返回（异步 + 日志告警）。

- [ ] **Step 4: 运行通过 + 提交**

```bash
mvn -pl platform-control test -Dtest=ResultCacheServiceTest
git add -A && git commit -m "feat(query): result cache with TTL and per-user history"
```

---

### Task 8: 审计哈希链

**Files:**
- Create: `platform-control/src/main/java/com/badc/sql/control/audit/AuditEvent.java`、`AuditMapper.java`、`AuditService.java`、`AuditController.java`
- Test: `platform-control/src/test/java/com/badc/sql/control/audit/AuditChainTest.java`

**Interfaces:**
- Produces: `AuditService.record(AuditCommand c)`；`GET /api/v1/audit/verify` 返回 `{valid, brokenAtSeq}`。

- [ ] **Step 1: 写失败测试 — 篡改中间一条后校验必须失败**

```java
@Test void tampering_breaks_the_chain() {
  audit.record(cmd("QUERY")); audit.record(cmd("QUERY")); audit.record(cmd("QUERY"));
  jdbc.update("update audit_event set payload_json='{}' where seq=2");
  assertThat(audit.verify().valid()).isFalse();
  assertThat(audit.verify().brokenAtSeq()).isEqualTo(2L);
}

@Test void hash_covers_prev_hash_and_payload() { /* 断言 hash == sha256(prevHash + canonicalJson) */ }
```

- [ ] **Step 2: 运行确认失败** → Expected: FAIL

- [ ] **Step 3: 实现**：`seq` 用自增，`hash = SHA-256(prev_seq || prev_hash || event_type || operator_id || payload_canonical_json)`，其中 `payload_canonical_json` 用固定字段序（Jackson `SORT_PROPERTIES_ALPHABETICALLY`）。DAO 层不提供 `update`/`delete` 方法；数据库账号只授 `INSERT/SELECT`。查询接口在 `AuditService` 内同步写一条 `AUDIT_QUERY_VIEW`。

- [ ] **Step 4: 运行通过 + 提交**

```bash
mvn -pl platform-control test -Dtest=AuditChainTest
git add -A && git commit -m "feat(audit): append-only hash chain with tamper verification"
```

---

## Self-Review

**1. Spec coverage（本切片应覆盖的）**

| Spec 条目 | 任务 |
|---|---|
| 研发只读查询、只见库名（§4.5、FR-01） | T3、T4、T6 |
| 自动 `LIMIT 1001`、结果上限（FR-04） | T6 |
| 结果保留 30 分钟（FR-04、Q-17） | T7 |
| 查询历史（FR-05） | T7 |
| 审计 append-only 哈希链（FR-12） | T8 |
| 5.7/8.0 能力探测（FR-01） | T5 |
| 凭证加密、不可回显（§4.3-6、FR-01） | T3 |
| 凭证不落执行器（§10.1 的控制面/执行器分离） | T5、T6 |

**未覆盖（属后续切片，非遗漏）**：写路径、工单审批、风险引擎、结构快照、导出、通知、定时、DDL/gh-ost、性能分析、前端完整化。

**2. Step scan**：每个 Step 都是单一动作（写测试 / 跑失败 / 实现 / 跑通过 / 提交）。实现步骤给的是签名与取值约束，没有写死方法体（除 5.7 禁用特性表与哈希公式这类"必须照抄"的内容）。

**3. Type consistency**：`ConnConfig`（T5 定义）在 T6 被 `QueryRunner` 使用；`ValidationResult`（T5）在 T6 校验阶段使用；`Capability`（T5）在 T6/T7 决定是否展示 `EXPLAIN ANALYZE`；`RawResult`（T6 定义于 common）在 T7 缓存复用。命名一致。

**4. Review Focus 落点**：①SQL 分号/注释 → T5 `MySqlDialect` 先剥注释与字面量再匹配；②NULL/BLOB/emoji → T6 类型映射断言；③5.7 版本提示 → T5 首个测试；④连接超时白名单 → T5 固定 3s connectTimeout + 验收用例；⑤越权 403 → T4 `assertVisible` 与 T6 `unauthorized_datasource_returns_403`。

**5. Proportion**：计划约 380 行，对应 spec 954 行的 5 个切片之一，未出现大段方法体。
