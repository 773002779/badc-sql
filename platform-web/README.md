# BADC SQL 控制台（platform-web）

生产库 SQL 执行平台的前端控制台：查询、变更工单、临时执行、通知、授权、审计与系统配置。

## 技术栈

React 18 · TypeScript · Vite 6 · Ant Design 5 · React Router 6 · TanStack Query 5 · CodeMirror 6

## 本地运行

```bash
cd platform-web
npm install
npm run dev          # http://127.0.0.1:5173
```

开发服务器把 `/api` 代理到控制面（默认 `http://127.0.0.1:8080`，可用 `BADCSQL_API` 覆盖）：

```bash
BADCSQL_API=http://127.0.0.1:8080 npm run dev
```

需要先在 `platform-control` 里建好库、跑过 Flyway 迁移，并有一个可登录的账号。

## 构建与检查

```bash
npm run typecheck    # tsc --noEmit
npm run build        # 类型检查 + vite build → dist/
npm run smoke        # 需先 npm run dev；用本机 Chrome 跑页面冒烟（mock API）
npm run smoke:real   # 真后端联调：需要 control:8080 + executor:8081 + 种子数据
```

冒烟脚本 `e2e/smoke.mjs` 用 mock API 走完全部页面并截图到 `.screens/`，同时收集控制台错误。
联调脚本 `e2e/real.mjs` 不拦截接口，真实走一遍登录 → 查询 → 提交工单 → 工单详情，
需要本地有可登录账号与一个可见数据源。可用 `CHROME_PATH` / `APP_URL` 指定浏览器与地址。

## 目录

```
src/
  api/         # http 封装（JWT、错误信封）、接口函数、与后端一一对应的类型
  auth/        # 登录态（token + 用户信息）
  components/  # SQL 编辑器、结果表格、风险徽标、状态标签
  hooks/       # 组织数据（系统 / 模块 / 数据源）
  layout/      # 侧栏 + 顶栏 + 明暗切换
  lib/         # 时间格式化、文案映射、SQL 拆分
  pages/       # 登录、查询、历史、工单、临时执行、通知、授权、审计、配置
  theme/       # antd 主题算法 + 设计令牌
```

## 一期范围与已知限制

已实现：登录、只读查询工作台（含结果表格与截断提示）、历史记录、变更工单（提交 / 审批 / 风险确认 / 执行 / 回滚 / 结构差异 / 快照）、运维临时执行（L3 转单）、通知中心、权限申请与审批、审计日志与哈希链校验、系统 / 模块 / 数据源配置。

暂未实现（后端也尚无接口，二期再加）：

- 查询结果导出与导出审批（FR-04 的导出部分）
- 性能分析（EXPLAIN / EXPLAIN ANALYZE，FR-11）
- 文件导入、定时执行、收藏分享（P1）
- 数据源账号集与连通测试的界面、主运维 / 组长角色配置页
- 角色 `/auth/me`：后端登录只返回 `token/userId/loginName`，前端不做角色判定，
  运维/管理员页面由后端 403 兜底；二期建议加一个只读的当前用户接口以精确隐藏入口

生产部署：控制面的 Spring Security 目前只放行 `/api/**`，因此 SPA 需要由网关/反代同源托管，
或由后端显式放行静态资源与 history fallback。本期只提供开发模式与 `dist/` 产物。
