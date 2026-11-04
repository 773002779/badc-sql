/**
 * 前端冒烟测试：用 mock API 走一遍所有页面，截图并收集控制台错误。
 *
 * 依赖：playwright-core（复用本机 Chrome，不下载浏览器）。
 * 运行：先 `npm run dev`，再 `node e2e/smoke.mjs`
 */
import { chromium } from 'playwright-core';
import { mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const SHOTS = join(__dirname, '..', '.screens');
mkdirSync(SHOTS, { recursive: true });

const BASE = process.env.APP_URL ?? 'http://127.0.0.1:5173';
const CHROME =
  process.env.CHROME_PATH ?? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';

const now = '2026-11-05T02:20:00Z';

const systems = [
  { id: 1, name: '订单中心' },
  { id: 2, name: '支付中心' },
];

const modules = [
  { id: 11, systemId: 1, name: '订单查询' },
  { id: 12, systemId: 1, name: '订单变更' },
  { id: 21, systemId: 2, name: '支付回调' },
];

const datasources = [
  { id: 101, name: '订单库-生产', dbName: 'order_db', systemId: 1, type: 'mysql' },
  { id: 102, name: '支付库-生产', dbName: 'pay_db', systemId: 2, type: 'mysql' },
];

const queryResult = {
  resultId: '8f14e45f-ceea-467f-a1f0-1c1a5a7c9e01',
  columns: [
    { name: 'id', type: 'BIGINT' },
    { name: 'order_no', type: 'VARCHAR' },
    { name: 'amount', type: 'DECIMAL' },
    { name: 'status', type: 'TINYINT' },
    { name: 'remark', type: 'TEXT' },
    { name: 'created_at', type: 'DATETIME' },
  ],
  rows: [
    [1, 'SO202610310001', 128.5, 2, null, '2026-10-31T02:00:00Z'],
    [2, 'SO202610310002', 88.0, 1, '加急', '2026-10-31T02:03:11Z'],
    [3, 'SO202610310003', 1024.99, 3, null, '2026-10-31T02:11:45Z'],
    [4, 'SO202610310004', 45.2, 2, '客户要求拆单', '2026-10-31T03:02:02Z'],
  ],
  truncated: false,
  elapsedMs: 37,
};

const ticketSummaries = [
  {
    id: 1,
    status: 'REVIEWING_L1',
    riskLevel: 'L3',
    systemId: 1,
    moduleId: 12,
    datasourceId: 101,
    title: '修正订单金额异常数据',
    submitterId: 3,
    ticketType: 'NORMAL',
    createdAt: '2026-11-04T06:12:00Z',
    expectedExecuteAt: '2026-11-05T14:00:00Z',
  },
  {
    id: 2,
    status: 'APPROVED',
    riskLevel: 'L2',
    systemId: 1,
    moduleId: 12,
    datasourceId: 101,
    title: '补建订单索引 idx_order_no',
    submitterId: 3,
    ticketType: 'NORMAL',
    createdAt: '2026-11-03T09:30:00Z',
    expectedExecuteAt: '2026-11-05T15:00:00Z',
  },
  {
    id: 3,
    status: 'SUCCEEDED',
    riskLevel: 'L1',
    systemId: 2,
    moduleId: 21,
    datasourceId: 102,
    title: '支付回调日志表新增字段',
    submitterId: 4,
    ticketType: 'NORMAL',
    createdAt: '2026-11-02T01:00:00Z',
    expectedExecuteAt: '2026-11-02T06:00:00Z',
  },
  {
    id: 4,
    status: 'REVIEWING_L1',
    riskLevel: 'L3',
    systemId: 1,
    moduleId: 12,
    datasourceId: 101,
    title: '运维临时执行转单：truncate t_order_log',
    submitterId: 2,
    ticketType: 'TEMP_EXEC',
    createdAt: '2026-11-04T11:20:00Z',
    expectedExecuteAt: '2026-11-05T14:00:00Z',
  },
];

const ticketDetail = {
  id: 1,
  status: 'REVIEWING_L1',
  riskLevel: 'L3',
  ticketType: 'NORMAL',
  systemId: 1,
  moduleId: 12,
  datasourceId: 101,
  title: '修正订单金额异常数据',
  changeReason:
    '2026-10-31 批量下单任务把 amount 写成了分，需要按订单号回刷为元，影响 3 条订单。',
  rollbackPlan: '使用下方回滚 SQL 把 amount 恢复为原值；执行前已导出受影响订单号清单。',
  rollbackSql: 'update t_order set amount = 128.50 where id = 1;',
  statementFingerprint: '3f9a1c0b7e2d4f8a9c1b6d5e4a3f2c1b',
  snapshotEnabled: true,
  riskSnapshotHash: '9c1b6d5e4a3f2c1b3f9a1c0b7e2d4f8a',
  riskSnapshot: {
    level: 'L3',
    hits: [
      { rule: 'NO_WHERE', level: 'L3', message: 'update 未带 where 条件', table: 't_order' },
      { rule: 'LARGE_TABLE', level: 'L2', message: '目标表行数 1,204,558，超过阈值', table: 't_order' },
    ],
  },
  submitterId: 3,
  originTicketId: null,
  rollbackReason: null,
  expectedExecuteAt: '2026-11-05T14:00:00Z',
  createdAt: '2026-11-04T06:12:00Z',
  updatedAt: '2026-11-04T06:12:00Z',
  approvedAt: null,
  executedAt: null,
  finishedAt: null,
  notificationFailures: 1,
  statements: [
    {
      seq: 1,
      statementType: 'UPDATE',
      riskLevel: 'L3',
      status: 'PENDING',
      affectedRows: null,
      errorMessage: null,
      elapsedMs: null,
      executedAt: null,
      sql: "update t_order set amount = amount / 100 where order_no like 'SO20261031%';",
    },
    {
      seq: 2,
      statementType: 'SELECT',
      riskLevel: 'L0',
      status: 'PENDING',
      affectedRows: null,
      errorMessage: null,
      elapsedMs: null,
      executedAt: null,
      sql: "select id, amount from t_order where order_no like 'SO20261031%';",
    },
  ],
  reviews: [],
  snapshots: [
    {
      changeSeq: 1,
      snapshotRef: '/data/snapshot/1/101/1/1/schema.json',
      tableFingerprints: { t_order: { columns: ['id', 'order_no', 'amount'] } },
      capturedAt: '2026-11-04T07:00:00Z',
    },
  ],
  events: [
    {
      eventType: 'TICKET_SUBMITTED',
      actorId: 3,
      detail: '{"riskLevel":"L3"}',
      createdAt: '2026-11-04T06:12:00Z',
    },
  ],
};

const notifications = [
  {
    id: 1,
    eventType: 'TICKET_SUBMITTED',
    systemId: 1,
    ticketId: 1,
    grantRequestId: null,
    channel: 'IN_APP',
    status: 'SENT',
    title: '工单待审核：修正订单金额异常数据',
    body: '订单中心 / 订单变更 / L3，请及时审核。',
    attempts: 1,
    lastError: null,
    createdAt: '2026-11-04T06:12:01Z',
    sentAt: '2026-11-04T06:12:02Z',
  },
  {
    id: 2,
    eventType: 'TICKET_SUBMITTED',
    systemId: 1,
    ticketId: 1,
    grantRequestId: null,
    channel: 'WECOM',
    status: 'FAILED',
    title: '工单待审核：修正订单金额异常数据',
    body: '订单中心 / 订单变更 / L3，请及时审核。',
    attempts: 3,
    lastError: '企微返回 40001 invalid credential',
    createdAt: '2026-11-04T06:12:01Z',
    sentAt: null,
  },
];

const grantRequests = [
  {
    id: 1,
    userId: 3,
    moduleId: 11,
    canExport: true,
    reason: '排查订单对账差异，需要导出明细',
    status: 'APPROVED',
    reviewerId: 2,
    reviewComment: '同意，仅限本月对账',
    createdAt: '2026-11-01T02:00:00Z',
    reviewedAt: '2026-11-01T05:00:00Z',
  },
  {
    id: 2,
    userId: 4,
    moduleId: 21,
    canExport: false,
    reason: '支付回调排查',
    status: 'PENDING',
    reviewerId: null,
    reviewComment: null,
    createdAt: '2026-11-04T03:00:00Z',
    reviewedAt: null,
  },
];

const auditRows = [
  {
    seq: 101,
    event_type: 'TICKET_SUBMITTED',
    operator_id: 3,
    exec_identity: null,
    datasource_id: 101,
    action: 'submit',
    object_id: 'ticket:1',
    payload_json: '{"riskLevel":"L3","statements":2}',
    prev_hash: 'a1b2c3',
    hash: 'd4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5',
  },
  {
    seq: 102,
    event_type: 'QUERY',
    operator_id: 3,
    exec_identity: 'readonly@ds-101',
    datasource_id: 101,
    action: 'query',
    object_id: '8f14e45f',
    payload_json: '{"rows":4,"truncated":false}',
    prev_hash: 'd4e5f6',
    hash: 'e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6',
  },
];

function ok(body) {
  return {
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(body),
  };
}

async function handleApi(route) {
  const request = route.request();
  const url = new URL(request.url());
  const path = url.pathname.replace(/^\/api\/v1/, '');
  const method = request.method();

  if (method === 'POST' && path === '/auth/login') {
    return route.fulfill(ok({ token: 'mock-token', userId: 3, loginName: 'laowang' }));
  }
  if (method === 'GET' && path === '/systems') return route.fulfill(ok(systems));
  if (method === 'GET' && path === '/modules') return route.fulfill(ok(modules));
  if (method === 'GET' && path === '/datasources') return route.fulfill(ok(datasources));
  if (method === 'GET' && path === '/datasources/101') {
    return route.fulfill(
      ok({
        ...datasources[0],
        capabilities: {
          versionString: '8.0.36',
          version: 'MYSQL_8_0',
          supportsExplainAnalyze: true,
          supportsInstantDdl: true,
          binlogEnabled: true,
          onlineDdlAvailable: false,
        },
      }),
    );
  }
  if (method === 'POST' && path === '/query') return route.fulfill(ok(queryResult));
  if (method === 'GET' && path === '/history') {
    return route.fulfill(
      ok({
        total: 1,
        page: 1,
        size: 20,
        items: [
          {
            id: 9,
            datasource_id: 101,
            sql_text: "select id, amount from t_order where order_no like 'SO2026%' limit 1001",
            row_count: 4,
            elapsed_ms: 37,
            status: 'SUCCESS',
            created_at: '2026-11-04T06:20:00Z',
          },
        ],
      }),
    );
  }
  if (method === 'GET' && path === '/tickets') {
    return route.fulfill(ok({ total: ticketSummaries.length, page: 1, size: 20, items: ticketSummaries }));
  }
  if (method === 'GET' && path === '/tickets/1/notifications') return route.fulfill(ok([]));
  if (method === 'GET' && path === '/tickets/1/schema-diff') {
    return route.fulfill(
      ok({
        tables: [
          {
            table: 't_order',
            status: 'CHANGED',
            columns: [
              { name: 'remark', status: 'ADDED', before: null, after: 'varchar(255)' },
              { name: 'amount', status: 'CHANGED', before: 'decimal(10,2)', after: 'decimal(12,2)' },
            ],
            indexes: [],
            triggers: [],
          },
        ],
      }),
    );
  }
  if (method === 'GET' && path === '/tickets/1') return route.fulfill(ok(ticketDetail));
  if (method === 'POST' && /^\/tickets\/\d+\/risk-ack$/.test(path)) {
    return route.fulfill(
      ok({ ticketId: 1, stage: 'EXECUTE', riskSnapshotHash: 'abc', acknowledgedAt: now }),
    );
  }
  if (method === 'POST' && /^\/tickets\/\d+\/execute$/.test(path)) {
    return route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify({ id: 1, status: 'EXECUTING' }) });
  }
  if (method === 'POST' && path === '/temp-executions') {
    return route.fulfill(
      ok({
        executed: true,
        riskLevel: 'L2',
        selfApproved: false,
        results: [{ seq: 1, success: true, affectedRows: 3, elapsedMs: 12, errorMessage: null }],
      }),
    );
  }
  if (method === 'GET' && path === '/notifications') {
    return route.fulfill(ok({ total: notifications.length, page: 1, size: 20, items: notifications }));
  }
  if (method === 'GET' && path === '/grants/me') {
    return route.fulfill(ok({ modules: [11, 12], datasources: [101] }));
  }
  if (method === 'GET' && path === '/grant-requests') {
    const scope = url.searchParams.get('scope') ?? 'mine';
    const items = scope === 'pending' ? grantRequests.filter((item) => item.status === 'PENDING') : grantRequests;
    return route.fulfill(ok({ total: items.length, page: 1, size: 20, items }));
  }
  if (method === 'GET' && path === '/audit/tail') return route.fulfill(ok(auditRows));
  if (method === 'GET' && path === '/audit/verify') {
    return route.fulfill(ok({ valid: true, brokenAtSeq: null }));
  }
  return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 'NOT_FOUND', message: `mock 未实现：${method} ${path}` }) });
}

const errors = [];

/** antd 对 Dayjs 表单值会打一条 dev-only 的循环引用告警；favicon 不是业务错误。 */
const IGNORED = [/favicon\.ico/, /There may be circular references/];
const record = (text) => {
  if (!IGNORED.some((pattern) => pattern.test(text))) errors.push(text);
};

const browser = await chromium.launch({ executablePath: CHROME, headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
await context.route(
  (url) => url.pathname.startsWith('/api/'),
  handleApi,
);

const page = await context.newPage();
page.on('console', (message) => {
  if (message.type() === 'error') record(`console: ${message.text()}`);
});
page.on('pageerror', (error) => record(`pageerror: ${error.message}`));

async function shot(name, fullPage = true) {
  await page.waitForTimeout(400);
  await page.screenshot({ path: join(SHOTS, `${name}.png`), fullPage });
  console.log(`shot: ${name}`);
}

async function goto(path, waitFor) {
  await page.goto(`${BASE}${path}`, { waitUntil: 'networkidle' });
  if (waitFor) await page.getByText(waitFor, { exact: false }).first().waitFor({ timeout: 8000 });
}

// 1) 登录页（亮/暗）
await goto('/login', '登录控制台');
await shot('01-login-light', false);

await page.evaluate(() => localStorage.setItem('badcsql.theme', 'dark'));
await page.reload({ waitUntil: 'networkidle' });
await shot('02-login-dark', false);

// 2) 登录 → 查询工作台（暗色）
await page.getByPlaceholder('登录名').fill('laowang');
await page.getByPlaceholder('密码').fill('secret');
await page.getByRole('button', { name: /登\s*录/ }).click();
await page.waitForURL('**/query');
await page.getByText('查询工作台').first().waitFor();
await shot('03-query-dark');

// 3) 跑一次查询（mock）
await page.getByRole('button', { name: '填入示例' }).click();
await page.getByRole('button', { name: /运行查询/ }).click();
await page.getByText('SO202610310001').first().waitFor({ timeout: 8000 });
await shot('04-query-result-dark');

// 4) 切回亮色
await page.getByRole('button', { name: '切换到亮色' }).click();
await shot('05-query-result-light');

// 5) 各页面
await goto('/tickets', '变更工单');
await shot('06-tickets-light');

await page.getByText('修正订单金额异常数据').first().click();
await page.waitForURL('**/tickets/1');
await page.getByText('风险提示').first().waitFor();
await shot('07-ticket-detail-light');

await page.getByRole('tab', { name: /语句/ }).click();
await shot('08-ticket-statements-light');

await goto('/history', '历史记录');
await shot('09-history-light');

await goto('/temp-exec', '临时执行');
await shot('10-temp-exec-light');

await goto('/notifications', '通知中心');
await shot('11-notifications-light');

await goto('/grants', '权限申请');
await shot('12-grants-light');

await goto('/audit', '审计日志');
await shot('13-audit-light');

await goto('/admin', '系统配置');
await shot('14-admin-light');

// 暗色再截一张工作台/工单
await page.getByRole('button', { name: '切换到暗色' }).click();
await goto('/tickets', '变更工单');
await shot('15-tickets-dark');

await page.evaluate(() => localStorage.setItem('badcsql.theme', 'light'));
await browser.close();

if (errors.length) {
  console.error(`\n发现 ${errors.length} 个前端错误：`);
  errors.forEach((item) => console.error(` - ${item}`));
  process.exit(1);
}
console.log('\n冒烟通过：所有页面渲染成功，无控制台错误。');
