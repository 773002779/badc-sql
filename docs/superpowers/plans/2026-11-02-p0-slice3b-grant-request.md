# 切片 3-B：授权申请与审批（§4.4 ②③）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 研发自助申请"模块授权（查询 + 提单，可选导出）"，该系统运维审批后写入授权，全程留审计并通知相关人。

**Architecture:** 申请单是独立实体 `grant_request`（规格 §5.2 的逻辑模型漏了它，本计划补上）。审批通过时**不直接改授权表**，而是在同一事务内写 `user_module_grant`（幂等 upsert）+ 迁移申请单状态（乐观并发）+ 写审计 + 发布通知事件。审批人判定复用切片 2 的口径：该系统 `sys_system_operator` 成员，且非 ADMIN、非申请人。

**Tech Stack:** 同切片 3-A。依赖 3-A 的 `NotificationDispatcher`（Task 5）与 `mapNotification` 事件（可先留空实现，见 Task 5 Step 3 的说明）。

**Spec:** `docs/superpowers/specs/2026-10-20-sql-execution-platform-design.md`（§4.4 第 168–186 行；权限矩阵第 145–155 行；§4.3-2 四眼原则）

## Global Constraints

- 迁移文件用 `V5__grant_request.sql`；生效顺序必须在 `V4`（通知表）之后。
- 时间 `DATETIME(3)` UTC；`utf8mb4_0900_ai_ci`；不建外键。
- **授权粒度沿用既有语义**：`user_module_grant` 存在 = 可查询 + 可提单；`can_export` 单独控制导出。**不新增** `can_query` / `can_submit` 列——切片 1/2 已把"有授权 ⇒ 可见 ⇒ 可查/可提单"写进 `VisibilityService` 与 `TicketAssignmentService`，加列会改变已交付语义并需要回填历史数据。
- **不自审**：申请人不能审批自己的申请（§4.3-2 的四眼原则在授权上的对应物）。
- **ADMIN 不参与授权审批**：与 `TicketReviewService.requireReviewable` 一致——管理员只做配置，审批权在系统运维。
- 所有状态迁移用 `where status = ?` 乐观并发；影响行数 0 → 409。
- 全部申请与审批留审计。

## Review Focus

1. **自我提权**：申请人自己审批通过，凭空拿到授权。必须有测试断言 403 **且** `user_module_grant` 无新行。
2. **重复/并发审批**：两个运维同时点通过，写了两次授权或状态跳到非法值。要求 409 且授权表只有一行。
3. **导出权限被降级**：已有 `can_export=1` 的人，其"仅查询"申请被批准后导出权限被清成 0。upsert 必须是 `greatest(旧值, 新值)`。
4. **无人可批**：模块所属系统没有运维归属，申请永远停在 PENDING 而申请人不知道。提交时就该拒绝并说明原因（与 §4.7 组长缺位拦截同思路）。
5. **越权审批**：非该系统运维（哪怕同角色）审批成功。
6. **申请一个不存在的模块**：返回 404 而不是 500 或静默成功。

---

### Task 1: `grant_request` 表

**Files:**
- Create: `platform-control/src/main/resources/db/migration/V5__grant_request.sql`
- Test: `platform-control/src/test/java/com/badc/sql/control/MigrationIT.java`（追加断言）

**Interfaces:**
- Produces: 表 `grant_request`。

- [ ] **Step 1: 写迁移**

列：`id BIGINT PK AUTO_INCREMENT`、`user_id BIGINT NOT NULL`（申请人）、`module_id BIGINT NOT NULL`、`can_export TINYINT(1) NOT NULL DEFAULT 0`（申请的导出权）、`reason VARCHAR(500) NOT NULL`、`status VARCHAR(16) NOT NULL DEFAULT 'PENDING'`、`reviewer_id BIGINT NULL`、`review_comment VARCHAR(500) NULL`、`created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)`、`reviewed_at DATETIME(3) NULL`。索引：`(user_id, status)`、`(module_id, status)`、`(status, created_at)`。CHECK：`status IN ('PENDING','APPROVED','REJECTED','CANCELLED')`。

**不为"同一人同一模块只能有一条 PENDING"建唯一索引**：MySQL 唯一索引无法表达"仅对 PENDING 去重"的条件约束，改由服务层在事务内先查后插（并在 Task 3 用测试覆盖并发下的重复）。

- [ ] **Step 2: 在 `MigrationIT` 断言表与 CHECK 存在**
- [ ] **Step 3: 跑测试**（命令同 3-A Task 1）
- [ ] **Step 4: 提交**：`git commit -m "feat(grant): add grant_request table"`

---

### Task 2: 状态枚举与仓储（乐观并发）

**Files:**
- Create: `platform-control/src/main/java/com/badc/sql/control/grant/GrantRequestStatus.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/grant/GrantRequestStore.java`
- Test: `platform-control/src/test/java/com/badc/sql/control/grant/GrantRequestStoreIT.java`

**Interfaces:**
- Produces:
  - `enum GrantRequestStatus { PENDING, APPROVED, REJECTED, CANCELLED }`
  - `record GrantRequestRow(long id, long userId, long moduleId, boolean canExport, String reason, String status, Long reviewerId, String reviewComment, Instant createdAt, Instant reviewedAt)`
  - `long GrantRequestStore.insert(long userId, long moduleId, boolean canExport, String reason)`
  - `Optional<GrantRequestRow> GrantRequestStore.findById(long id)`
  - `Optional<GrantRequestRow> GrantRequestStore.findPending(long userId, long moduleId)`
  - `int GrantRequestStore.moveStatus(long id, GrantRequestStatus from, GrantRequestStatus to, Long reviewerId, String comment, Instant reviewedAt)` —— 返回影响行数，0 表示被并发改过
  - `List<GrantRequestRow> GrantRequestStore.pageForUser(long userId, int limit, int offset)`、`pagePendingForSystems(long reviewerId, int limit, int offset)`

- [ ] **Step 1: 写失败测试**：`moveStatus(id, PENDING, APPROVED, ...)` 返回 1；再调一次（from 仍 PENDING）返回 0；`findPending` 在已批准后返回空。
- [ ] **Step 2–4:** 红 → 实现 → 绿（SQL 常量 + `RowMapper`，风格对齐 `TicketReviewMapper`）。
- [ ] **Step 5: 提交**：`git commit -m "feat(grant): grant request store with optimistic status transitions"`

---

### Task 3: 提交申请

**Files:**
- Create: `platform-control/src/main/java/com/badc/sql/control/grant/GrantRequestCommand.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/grant/GrantRequestService.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/grant/GrantRequestController.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/grant/SystemOperatorMapper.java`（`boolean hasOperator(long systemId)`）
- Test: `platform-control/src/test/java/com/badc/sql/control/grant/GrantRequestSubmitIT.java`

**Interfaces:**
- Consumes: `GrantRequestStore`（Task 2）、`ModuleMapper.findById`、`SystemOperatorMapper.hasOperator`
- Produces: `POST /api/v1/grant-requests`，请求体 `record GrantRequestCommand(Long moduleId, boolean canExport, String reason)`，返回 `201 {id, status}`。

校验顺序（**顺序本身是契约**，前一条失败就不该看到后一条的报错）：

1. 模块不存在 → 404「模块不存在：{id}」；
2. `reason` 为空 → 400「申请理由不能为空」（长度 ≤ 500）；
3. 该系统**没有任何运维归属** → 400「该系统还没有运维，申请无人可批，请联系管理员」；
4. 已有同模块的 `PENDING` 申请 → 409「你已有一条待审批的申请」；
5. 已持有该模块授权且本次不申请导出（或已可导出）→ 409「你已有该模块权限，无需申请」。

写库后：写审计 `GRANT_REQUESTED`，发布通知事件 `GRANT_REQUESTED`（收件人=系统运维）。

- [ ] **Step 1: 写失败测试**（4 条，每条一个断言焦点）

```java
@Test
void a_request_without_reason_is_refused() { ... assertBadRequest(..., "申请理由"); }
@Test
void a_second_pending_request_for_the_same_module_conflicts() { ... assertStatus(409); }
@Test
void a_system_without_operators_cannot_receive_requests() { ... assertBadRequest(..., "还没有运维"); }
@Test
void a_successful_request_is_pending_and_audited() { ... assertThat(id).isPositive(); assertAudit("GRANT_REQUESTED"); }
```

- [ ] **Step 2: 跑测试确认失败**
- [ ] **Step 3: 实现**（`@Transactional`；发布事件用 `ApplicationEventPublisher`）
- [ ] **Step 4: 跑测试确认通过**
- [ ] **Step 5: 提交**：`git commit -m "feat(grant): submit grant requests with actionable refusals"`

---

### Task 4: 审批与驳回（含授权写入）

**Files:**
- Modify: `platform-control/src/main/java/com/badc/sql/control/grant/GrantRequestService.java`
- Modify: `platform-control/src/main/java/com/badc/sql/control/grant/GrantRequestController.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/grant/UserModuleGrantStore.java`
- Test: `platform-control/src/test/java/com/badc/sql/control/grant/GrantRequestReviewIT.java`

**Interfaces:**
- Consumes: `TicketReviewMapper.isOperatorOf(systemId, userId)`（**复用它，不另写一份归属判定**）、`UserRoleService.roleOf`
- Produces:
  - `POST /api/v1/grant-requests/{id}/approve`、`POST /api/v1/grant-requests/{id}/reject`，请求体 `record ReviewCommand(String comment)`
  - `UserModuleGrantStore.upsert(long userId, long moduleId, boolean canExport, Long grantedBy)` —— SQL 用
    `insert into user_module_grant (user_id, module_id, can_export, granted_by) values (?,?,?,?) on duplicate key update can_export = greatest(can_export, values(can_export)), granted_by = values(granted_by), granted_at = current_timestamp(3)`

审批校验顺序：

1. 申请单不存在 → 404；
2. 状态不是 `PENDING` → 409「该申请已处理」；
3. 审批人 == 申请人 → 403「不能审批自己的申请」；
4. `roleOf(reviewer) == "ADMIN"` → 403「管理员不参与授权审批」；
5. `isOperatorOf(module.systemId(), reviewer)` 为假 → 403「只能审批自己负责的系统」；
6. 驳回时 `comment` 为空 → 400。

通过：`upsert(..., canExport = request.canExport())` → `moveStatus(PENDING, APPROVED, ...)` 必须返回 1，否则 409。同一事务内写审计 `GRANT_APPROVED` / `GRANT_REJECTED`，提交后发通知给申请人。

- [ ] **Step 1: 写失败测试**（每条都要断言"授权表到底有没有多一行"）

```java
@Test
void an_applicant_cannot_approve_their_own_request() {
    assertForbidden(post("/api/v1/grant-requests/" + id + "/approve", DEV_TOKEN, json(Map.of()))); 
    assertThat(grantCount(DEV, MODULE)).isZero();
}
@Test
void approving_twice_is_a_conflict_and_writes_one_grant_only() { ... 409; grantCount == 1 }
@Test
void a_non_operator_cannot_approve() { ... 403; grantCount == 0 }
@Test
void approving_export_never_downgrades_an_existing_export_grant() { ... can_export 仍为 1 }
@Test
void rejecting_requires_a_comment_and_writes_no_grant() { ... 400; grantCount == 0 }
```

- [ ] **Step 2: 跑测试确认失败**
- [ ] **Step 3: 实现**
- [ ] **Step 4: 跑测试确认通过**
- [ ] **Step 5: 提交**：`git commit -m "feat(grant): approve and reject grant requests, writing grants only on approval"`

---

### Task 5: 查询端点与通知接线

**Files:**
- Modify: `platform-control/src/main/java/com/badc/sql/control/grant/GrantRequestController.java`
- Create: `platform-control/src/main/java/com/badc/sql/control/grant/GrantNotificationListener.java`
- Test: `platform-control/src/test/java/com/badc/sql/control/grant/GrantRequestQueryIT.java`

**Interfaces:**
- Produces:
  - `GET /api/v1/grant-requests?scope=mine`（申请人看自己的，倒序分页）
  - `GET /api/v1/grant-requests?scope=pending`（运维看待自己可批的：`module` 所属系统在自己归属内的 `PENDING`）
  - `GrantNotificationListener`：`@TransactionalEventListener(AFTER_COMMIT)`，把 `GRANT_REQUESTED / APPROVED / REJECTED` 交给 `NotificationDispatcher.dispatch`

- [ ] **Step 1: 写失败测试**：`scope=mine` 只返回本人；`scope=pending` 只返回自己运维的系统的（造一条别的系统的 PENDING，断言不出现）；研发调 `scope=pending` 返回空列表而不是 403（他不运维任何系统，语义上是"没有待我批的"）。
- [ ] **Step 2–4:** 红 → 实现 → 绿。

> 若 3-A 尚未落地，本任务的 listener 留一个空实现并在类注释里写明"依赖切片 3-A Task 5"，同时 `dispatch` 的调用点保留 TODO 与对应单号。**不允许**在这里静默吞掉通知失败。

- [ ] **Step 5: 提交**：`git commit -m "feat(grant): list endpoints and post-commit notifications"`

---

## 实施记录（2026-11-04，`be089f5`）

5 个任务一次落地，与计划的差异如下（都是"少建一个文件"或"多一条保险"，没有削弱任何一条约束）：

| 计划 | 实际 | 原因 |
|---|---|---|
| `SystemOperatorMapper.hasOperator` 单独建类 | 合进 `GrantRequestStore.hasOperator` | 只有一条 `count(*)`；同包同表，另开一个 `@Repository` 只是多一层跳转 |
| `GrantRequestQueryIT` 单独一个 IT 类 | 折进 `GrantRequestSubmitIT` | 两条列表端点与"提交后能查到"共享同一套夹具；多一个 Spring 上下文要多跑 20 秒 |
| `GrantRequestStore` 里没有 `findCanExport` | 放在 `UserModuleGrantStore` | 授权表的读写在同一个类里，"有没有授权"与"授权怎么升"是同一条口径 |
| 审批顺序：先 upsert 授权，再 `moveStatus` | 先 `moveStatus`，再 upsert | 两者在同一个事务里，顺序不影响原子性；先迁移状态可以让 409 直接短路，不做无用的授权写入 |
| `NotificationCommand` 携带 `canExport` | **不携带** | 白名单（R31）的 10 个键是上限，不为授权事件单独扩键。要看"批的是查询还是导出"点短链进详情 |
| `NotificationErrorText` 包内可见 | 改为 `public` | `grant` 包要复用同一份脱敏/截断逻辑；另写一份必然分叉 |

测试 26 条 IT，其中审批类**每条都同时断言"授权表有没有多一行"**——只断言 403 不够，自我提权最危险的表现是
"返回了错误，但授权已经写进去了"。`NotificationErrorText` 的可见性改动没有新增测试：它只改修饰符，行为由
既有的 `NotificationErrorTextTest`（16 条）守住。

**已知缺口（本计划不覆盖，也不假装覆盖）**：
1. `CANCELLED` 状态没有任何写入路径（申请人撤回属于后续切片），V5 的 CHECK 已为它留好位置；
2. 没有"同一人同一模块只能有一条 PENDING"的数据库级约束（MySQL 无法表达条件唯一索引）；服务层先查后插，
   并发下最坏是两条 PENDING，授权 upsert 幂等，不会写重复授权行；
3. 授权**收回**没有入口：`greatest(...)` 只增不减，"降级/撤销"需要独立的撤销流程与审计事件。

---

## 交接

5 个任务完成后，§4.4 的 ②③ 闭环：研发能自助申请、运维能审批、授权即时生效、全程审计与通知。

**明确不在本计划内**：§4.4 ④ 运维横向（申请其他系统的备运维、主运维配置他人为备运维、被配置人确认），它写的是 `sys_system_operator` 而不是 `user_module_grant`，且带"接收确认"流程，应单独出一份计划。
