# 切片 3 交付说明（通知 T8 + 授权申请/审批 + 结构前后对比）

日期：2026-11-04　提交：`a240997`（通知查询）、`be089f5`（授权申请与审批）、`0ca8ff8`（文档）

## 1. 交付内容

| 部分 | 内容 | 端点 |
|---|---|---|
| 3-A 任务 8 | 我的通知（分页）、某工单的通知记录、工单详情暴露投递失败数 | `GET /api/v1/notifications`、`GET /api/v1/tickets/{id}/notifications`、`TicketDetail.notificationFailures` |
| 3-B | 授权申请（研发自助）、审批/驳回（系统运维）、两条列表 | `POST /api/v1/grant-requests`、`POST /api/v1/grant-requests/{id}/approve|reject`、`GET /api/v1/grant-requests?scope=mine|pending` |
| FR-02 验收 1 | 执行后结构快照（`change_seq=2`）+ 变更前后字段级差异 | `GET /api/v1/tickets/{id}/schema-diff`（执行器侧 `POST /internal/v1/schema-diff`） |

数据模型新增一张表 `grant_request`（`V5__grant_request.sql`）。**授权粒度没有动**：
`user_module_grant` 存在 = 可查询 + 可提单，`can_export` 单独控制导出。

## 2. 验证

`.\mvnw.cmd -o clean verify` → **BUILD SUCCESS**，工作区干净。

| 模块 | 单元 | 集成 |
|---|---|---|
| platform-common | 17 | 0 |
| platform-executor | 85 | 42 |
| platform-control | 437 | 268 |
| **合计** | **539** | **310** |

合计 **849** 条（切片 3 之前是 809；本次 +40：通知查询 5、授权申请/审批 20、结构差异 14、其余为夹具与迁移断言）。

## 3. 本轮的关键判断

1. **申请"无人可批"在提交时就拒绝**（400），而不是让它停在 PENDING。否则申请人不知道自己等的那个审批人
   根本不存在——与 §4.7「组长缺位拦截」同一条思路。
2. **授权 upsert 只升不降**（`greatest(can_export, values(can_export))`）。批一条"仅查询"的申请，
   不能把对方已有的导出权悄悄抹掉。
3. **每条审批用例同时断言"授权表有没有多一行"**。只断言 403 不够：自我提权最危险的表现是
   "返回了错误，但授权已经写进去了"。
4. **`TICKET_` 前缀的事件类改名**。授权申请走同一条投递链路，`TicketNotificationEvent` 这个名字会让
   "工单侧"成为假话；改名后行为未变，由既有的 `TicketNotificationIT`（13 条）守住。
5. **`notificationFailures` 只数 `FAILED`**。`SKIPPED`（渠道未配置）不是失败——把"没配邮箱"显示成
   "通知发不出去"会让人去查一个不存在的故障。

### 3.5 变更前后结构对比（FR-02 验收 1）

6. **比对落盘的 `schema.json`，不是数据库里的指纹**。指纹是 `sha256(SHOW CREATE TABLE)`，只能回答
   "变没变"，回答不了"改了什么"；而差异的用途正是后者。结构化快照本来就已经落盘（列、索引、触发器），
   读回来比对即可，不需要第二次抓取，也不需要新表。
7. **执行后快照抓取失败不改工单状态**，与执行前**故意相反**：执行前抓不到 → 禁止执行（没有可回退的
   基线）；执行后抓不到 → 只记 warn（语句已经落地，把工单标成失败会让人以为"没改成功"）。
8. **没有执行后快照返回 409，而不是"没有差异"**。"没有差异"会被读成"这次变更什么都没改"，而事实是
   "根本没有可比对的东西"——这两句在审核场景里结论相反。
9. **差异算法放在执行器侧**：只有执行器挂着快照卷。控制面只负责可见性判定与取引用，不复制一套读文件逻辑。

---
## 4. 明确没做的（不假装做了）
| 项 | 影响 | 建议时机 |
|---|---|---|
| FR-02 的约束/分区/数据大小结构化字段、磁盘水位、180 天清理 | 快照内容与容量管理不完整（"变更前后差异"已交付，见 §3.5） | 快照真的占满盘时再做 |
| §4.4④ 运维横向（申请他人系统的备运维 + 被配置人确认） | 只能由管理员直接配 `sys_system_operator` | 有真实需求时再做 |
| 授权**收回/降级** | `greatest` 只增不减，撤销无入口 | 与"授权审计"一起做 |
| 申请人**撤回**申请（`CANCELLED`） | 枚举与 CHECK 已留位，无写入路径 | 体验反馈驱动 |
| 通知 outbox | 提交后、异步任务执行前崩溃会整条丢失（无 PENDING 行） | 与"通知可重试"一起做 |
| 通知开关的管理端点 | 改开关只能走数据库 | 前端需要时再包一层 |

## 5. 上线前要知道的

1. **`V5` 是新增表，不需要 `flyway repair`**；只有改过已应用版本（例如 V4 的校验和变化）才需要。
2. 授权申请的通知复用 `badcsql.notify.link-base`，生成 `{link-base}/grants/{id}`。前端还没有这个页面时，
   短链点开是 404——要么先不上 `link-base`，要么先把页面做出来。
3. `NotificationErrorText` 由包内可见改为 `public`（`grant` 包复用脱敏逻辑），行为未变。
4. **V3 里 `schema_snapshot.change_seq` 的列注释只写到 `1`**（执行后快照是后加的）。它不影响任何行为，
   改注释需要一次 `ALTER TABLE ... MODIFY COLUMN` 迁移，本轮没做；以 `SchemaSnapshotMapper` 的 javadoc 为准。
5. 执行后快照抓取失败时工单仍是成功的，只是 `schema-diff` 返回 409 并说明原因。**看到 409 不代表变更失败**。
6. 本轮**没有做**逐条变异验证（上一轮的纪律）。新增断言以"能否真的失败"为取舍标准，但**未逐条验证**，
   这是本轮刻意的取舍：优先把功能闭环，用真实使用反馈决定后面在哪里加密。
7. 结构差异**不经过执行器连库**：它只读快照文件，所以目标库宕机时依然可以看历史工单的差异。

## 6. 怎么试

```powershell
# 研发：我要模块 202 的查询权限
curl -H "Authorization: Bearer $DEV" -H 'Content-Type: application/json' `
     -d '{"moduleId":202,"canExport":false,"reason":"排查订单重复数据"}' `
     http://127.0.0.1:8080/api/v1/grant-requests

# 运维：待我审批
curl -H "Authorization: Bearer $OPS" 'http://127.0.0.1:8080/api/v1/grant-requests?scope=pending'

# 运维：通过
curl -X POST -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' `
     -d '{"comment":"已确认影响范围"}' `
     http://127.0.0.1:8080/api/v1/grant-requests/1/approve

# 研发：我的消息 / 我的申请
curl -H "Authorization: Bearer $DEV" 'http://127.0.0.1:8080/api/v1/notifications?page=1&size=20'
curl -H "Authorization: Bearer $DEV" 'http://127.0.0.1:8080/api/v1/grant-requests?scope=mine'

# 任何人（能看这张单）：变更前后的结构差异
curl -H "Authorization: Bearer $DEV" 'http://127.0.0.1:8080/api/v1/tickets/1/schema-diff'
```
