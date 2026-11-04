/**
 * 真后端联调脚本（需要 control:8080 + executor:8081 + 本地 MySQL 与种子数据）。
 * 覆盖：登录 → 数据源列表 → 真实查询 → 提交变更工单 → 工单详情。
 *
 * 运行：node e2e/real.mjs
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

const errors = [];
const IGNORED = [/favicon\.ico/, /There may be circular references/];
const record = (text) => {
  if (!IGNORED.some((pattern) => pattern.test(text))) errors.push(text);
};

const browser = await chromium.launch({ executablePath: CHROME, headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await context.newPage();
page.on('console', (message) => {
  if (message.type() === 'error') record(`console: ${message.text()}`);
});
page.on('pageerror', (error) => record(`pageerror: ${error.message}`));

const shot = async (name) => {
  await page.waitForTimeout(500);
  await page.screenshot({ path: join(SHOTS, `${name}.png`), fullPage: true });
  console.log(`shot: ${name}`);
};

// 1) 登录（真实 JWT）
await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
await page.getByPlaceholder('登录名').fill('dev1');
await page.getByPlaceholder('密码').fill('Passw0rd!');
await page.getByRole('button', { name: /登\s*录/ }).click();
await page.waitForURL('**/query');
await page.getByText('订单库-生产').first().waitFor({ timeout: 15_000 });
console.log('login + datasource list ok');
await shot('real-01-query');

// 2) 真实查询
await page.getByRole('button', { name: '填入示例' }).click();
await page.getByRole('button', { name: /运行查询/ }).click();
await page.getByText('demo').first().waitFor({ timeout: 15_000 });
await shot('real-02-query-result');

// 3) 历史记录（真实写入了 query_history）
await page.goto(`${BASE}/history`, { waitUntil: 'networkidle' });
await page.getByText('select 1 as demo').first().waitFor({ timeout: 15_000 });
await shot('real-03-history');

// 4) 提交变更工单
await page.goto(`${BASE}/tickets/new`, { waitUntil: 'networkidle' });
await page.locator('.ant-select-selector').first().click();
await page
  .locator('.ant-select-item-option')
  .filter({ hasText: '订单库-生产' })
  .first()
  .click();
await page.locator('#title').fill('联调测试：修正订单金额');
await page.locator('#changeReason').fill('本地联调验证提单流程，影响 1 条订单，金额按订单号回刷。');
await page.locator('.sql-editor-host').click();
await page.keyboard.type('update t_order set amount = 128.50 where id = 1;');
await page.locator('#rollbackSql').fill('update t_order set amount = 128.50 where id = 1;');
await shot('real-04-ticket-create');
await page.getByRole('button', { name: /提交工单/ }).click();
await page.waitForTimeout(3000);
if (!/\/tickets\/\d+$/.test(new URL(page.url()).pathname)) {
  const alerts = await page.locator('.ant-alert').allInnerTexts().catch(() => []);
  await shot('real-05-ticket-error');
  throw new Error(`提交未跳转，页面提示：${alerts.join(' | ') || '(无)'}`);
}
await page.getByText('风险提示').first().waitFor({ timeout: 15_000 });
console.log('ticket submitted, url =', page.url());
await shot('real-05-ticket-detail');

await browser.close();

if (errors.length) {
  console.error(`\n发现 ${errors.length} 个前端错误：`);
  errors.forEach((item) => console.error(` - ${item}`));
  process.exit(1);
}
console.log('\n真后端联调通过。');
