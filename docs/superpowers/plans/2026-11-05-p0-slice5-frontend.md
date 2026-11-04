# 切片 5：前端控制台（一期）

> 状态：一期完成，待真实环境验证后调整。
> 日期：2026-11-05

## 目标

给已完成的控制面接口补一个可用的前端：查询、变更工单、临时执行、通知、授权、审计与基础配置，
风格简洁、支持明暗切换。**一期先把主流程跑通**，不追求细节完备；真实使用后再迭代。

## 范围

**做**：登录、查询工作台、历史、工单（提交 / 审批 / 风险确认 / 执行 / 回滚 / 结构差异 / 快照）、
临时执行、通知中心、权限申请与审批、审计日志与链校验、系统 / 模块 / 数据源配置。

**不做**：导出、性能分析、文件导入、定时执行、收藏分享、账号集管理、角色配置页、SSO。

理由：后端尚未提供对应接口，先做能形成闭环的部分，避免前端对着不存在的接口写。

## 技术决策

| 决策 | 选择 | 理由 |
|---|---|---|
| 框架 | React 18 + TypeScript + Vite | 沿用切片 1 已定技术栈（React 18 + Ant Design） |
| 组件库 | Ant Design 5 | 内置暗色算法与中文 locale，企业内控台最省工 |
| 数据层 | TanStack Query | 列表分页、轮询执行状态、缓存失效都省手写 |
| 编辑器 | CodeMirror 6 + `@codemirror/lang-sql` | 比 Monaco 轻，暗色主题现成 |
| 部署形态 | 独立 `platform-web`，开发期 Vite 代理 `/api` | 控制面只放行 `/api/**`，不为此改后端安全配置 |
| 角色 | 不做前端角色判定，入口统一展示，越权由后端 403 兜底 | 登录响应没有角色字段；一期不新增后端接口 |
| 主题 | `ConfigProvider` 双算法 + CSS 变量，`localStorage` 持久化，默认跟随系统 | 首屏内联脚本先定主题，避免闪白 |

## 页面 ↔ 接口

| 页面 | 接口 |
|---|---|
| 登录 `/login` | `POST /api/v1/auth/login` |
| 查询 `/query` | `GET /datasources`、`POST /query` |
| 历史 `/history` | `GET /history?page&size` |
| 工单列表 `/tickets` | `GET /tickets?page&size` |
| 新建工单 `/tickets/new` | `POST /tickets`（多条 SQL 客户端按分号拆成语句数组） |
| 工单详情 `/tickets/:id` | `GET /tickets/{id}`、`/approve`、`/reject`、`/risk-ack`、`/execute`、`/rollback`、`/schema-diff`、`/notifications` |
| 临时执行 `/temp-exec` | `POST /temp-executions`（200 直接执行 / 202 转工单） |
| 通知 `/notifications` | `GET /notifications?page&size` |
| 权限申请 `/grants` | `GET /grants/me`、`GET /grant-requests?scope=mine|pending`、`POST`、`/approve`、`/reject` |
| 审计 `/audit` | `GET /audit/tail?limit`、`GET /audit/verify` |
| 配置 `/admin` | `GET/POST /systems`、`GET/POST /modules`、`GET/POST/DELETE /datasources` |

## 关键交互

- **执行前风险确认**：弹框展示风险等级与命中规则 → 勾选 → `POST /risk-ack` → `POST /execute`（202）。
  执行中详情每 2.5 秒轮询，直到状态离开 `EXECUTING`。
- **临时执行**：勾选确认后 `confirmed=true` 提交；L3 非组长返回 202，直接跳到生成的工单。
- **拆分 SQL**：后端禁止一条语句里多个分号，前端按引号 / 反引号 / 注释边界拆分（`lib/sql.ts`）。
- **回滚**：基于原单创建回滚工单，默认带入原单回滚 SQL，原因 ≥10 字。
- **错误**：统一解析 `{code,message}`；401 清登录态回登录页；403 在页面内提示"无权限"。

## 验证

| 项 | 结果 |
|---|---|
| `npm run typecheck` | 通过 |
| `npm run build` | 通过（antd 1.06MB / gzip 331KB，已按 react/antd/codemirror/query 拆包） |
| `npm run smoke` | 通过：15 张截图覆盖全部页面，0 控制台错误 |
| `npm run smoke:real` | 通过：真实登录 → 查询返回 4 行 → UI 提交工单 → 跳转详情 |

- `smoke` 用 Playwright 驱动本机 Chrome + mock API（`e2e/smoke.mjs`），不依赖后端与数据库。
- `smoke:real`（`e2e/real.mjs`）不拦截接口，需要 control:8080、executor:8081、本地 MySQL 与一个可见数据源。
  本次用临时 JDBC 种子（`dev1/ops1/lead1/admin1`，密码 `Passw0rd!`；订单中心 / 订单查询 / 订单库-生产），
  种子脚本放在仓库外的 `.tmp/`，未提交。

## 遗留（二期候选，按真实使用优先级排）

1. **当前用户接口**：`GET /api/v1/auth/me` 返回角色，用于隐藏运维/管理入口。
2. **导出**：结果导出与 >1000 行的审批流（后端接口未实现）。
3. **性能分析**：`EXPLAIN` / 8.0 的 `EXPLAIN ANALYZE`，5.7 隐藏入口。
4. **账号集与连通测试**：数据源详情页。
5. **生产托管**：网关同源托管 `dist/`，或后端放行静态资源 + history fallback。
6. 工单列表服务端筛选（当前只有分页，筛选在前端当页生效）。
7. 结果集 30 分钟过期的提示与"结果已过期"重跑引导。

## 经验（给后续实现者）

- 本仓库的 `pwsh` 实际是 Windows PowerShell 5.1：`Get-Content`/`Set-Content` 默认按系统 ANSI（GBK）处理无 BOM 文件，
  批量改含中文的源码会把 UTF-8 写坏。**改文件用编辑工具，或显式指定 `-Encoding UTF8`**；读中文文件用 UTF-8。
- Vite 开发模式下 Playwright 的 `**/api/**` 路由会连 `/src/api/*.ts` 一起拦截，应改用
  `url.pathname.startsWith('/api/')` 判定。
