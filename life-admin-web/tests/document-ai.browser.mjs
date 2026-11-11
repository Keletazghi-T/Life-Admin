import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { chromium } from 'playwright-core';
// Run only against receiptAiBrowser --args=--document-analysis (mocked HTTP, disposable data).
const base = 'http://localhost:3002';
test('document understanding: mocked analysis, confirmation, selected tasks, retry, manual fallback and isolation', { timeout: 150000 }, async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage(), errors = [], external = [];
  page.on('pageerror', error => errors.push(error.message));
  await context.route('**/*', route => { const url = new URL(route.request().url()); if (url.origin !== base) { external.push(url.origin); return route.abort(); } return route.continue(); });
  async function api(path) { const response = await context.request.get(base + path); assert.equal(response.status(), 200); return response.json(); }
  async function mutate(path, method, data) { const csrf = await api('/api/auth/csrf'); return context.request.fetch(base + path, { method, headers: { [csrf.headerName]: csrf.token }, data }); }
  async function register(targetPage) {
    await targetPage.goto(base + '/register'); await targetPage.getByLabel('Email', { exact: true }).fill(`m20-${randomUUID()}@example.test`);
    const password = `Fixture-${randomUUID()}`; await targetPage.getByLabel('Password', { exact: true }).fill(password); await targetPage.getByLabel('Confirm password', { exact: true }).fill(password);
    await targetPage.getByRole('button', { name: 'Create account', exact: true }).click(); await targetPage.waitForURL(base + '/');
  }
  async function upload() {
    const marker = await api('/api/test/receipt-ai'); assert.equal(marker.mocked, true, 'Must use the mock-only harness');
    const csrf = await api('/api/auth/csrf');
    const response = await context.request.post(base + '/api/documents/upload', { headers: { [csrf.headerName]: csrf.token }, multipart: { file: { name: 'Controlled document fixture.pdf', mimeType: 'application/pdf', buffer: await readFile(new URL('../../docs/fixtures/TestRecord.pdf', import.meta.url)) } } });
    assert.equal(response.status(), 201); const document = await response.json(); await page.goto(base + `/documents/${document.id}`); await page.getByRole('button', { name: 'Analyze document', exact: true }).waitFor(); return document.id;
  }
  try {
    await register(page); const id = await upload();
    assert.equal((await api('/api/test/receipt-ai')).calls, 0);
    await page.reload(); await page.getByRole('button', { name: 'Analyze document', exact: true }).waitFor();
    await page.waitForFunction(() => [...document.querySelectorAll('button')].some(button => button.textContent === 'Analyze document' && !button.disabled));
    await page.getByRole('button', { name: 'Analyze document', exact: true }).evaluate(button => { button.click(); button.click(); });
    await page.getByRole('heading', { name: 'Suggested by Life Admin', exact: true }).waitFor();
    assert.equal((await api('/api/test/receipt-ai')).calls, 1);
    assert.equal((await mutate(`/api/documents/${id}/tasks/generate`, 'POST')).status(), 409);
    assert.equal((await api(`/api/documents/${id}/tasks`)).length, 0);
    assert.equal(await page.getByRole('button', { name: 'Create selected tasks', exact: true }).count(), 0);
    await page.getByLabel('Simple summary', { exact: true }).fill('My checked explanation');
    await page.getByLabel('Important deadline', { exact: true }).fill('2026-10-16');
    await page.getByLabel('Recommended next steps (one per line)', { exact: true }).fill('Call the issuing organization\nPrepare identification');
    await page.getByRole('button', { name: 'Confirm document details', exact: true }).click();
    await page.getByRole('heading', { name: 'Confirmed by you', exact: true }).waitFor();
    await page.getByRole('checkbox', { name: 'Call the issuing organization', exact: true }).check();
    await page.getByRole('checkbox', { name: 'Deadline — 2026-10-16', exact: true }).check();
    await page.getByRole('button', { name: 'Create selected tasks', exact: true }).click();
    await page.getByText('Selected tasks are ready. Previously created actions were not duplicated.', { exact: true }).waitFor();
    assert.equal(await page.locator('.task-row').count(), 2);
    await page.getByRole('checkbox', { name: 'Call the issuing organization', exact: true }).check(); await page.getByRole('button', { name: 'Create selected tasks', exact: true }).click();
    await page.waitForFunction(() => ![...document.querySelectorAll('button')].find(button => button.textContent === 'Add task')?.disabled);
    assert.equal((await api(`/api/documents/${id}/tasks`)).length, 2);
    await page.reload(); await page.getByRole('heading', { name: 'Confirmed by you', exact: true }).waitFor();
    assert.equal((await api('/api/test/receipt-ai')).calls, 1); assert.equal((await api(`/api/documents/${id}/analysis`)).confirmedContent.summary, 'My checked explanation');
    await page.setViewportSize({ width: 390, height: 844 });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.getByRole('button', { name: 'Edit confirmed details', exact: true }).click(); assert.equal(await page.getByLabel('Simple summary', { exact: true }).inputValue(), 'My checked explanation');
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    const other = await browser.newContext(); const otherPage = await other.newPage(); await register(otherPage);
    assert.equal((await other.request.get(base + `/api/documents/${id}/analysis`)).status(), 404);
    assert.equal((await other.request.get(base + `/api/documents/${id}/analysis-attempt`)).status(), 404); await other.close();
    await mutate('/api/test/receipt-ai/fail', 'POST'); const failedId = await upload();
    await page.getByRole('button', { name: 'Analyze document', exact: true }).click(); await page.getByRole('alert').filter({ hasText: "couldn't analyze" }).waitFor();
    assert.equal((await api('/api/test/receipt-ai')).calls, 2);
    await page.reload(); await page.getByRole('button', { name: /Try analysis again/ }).waitFor(); assert.equal((await api('/api/test/receipt-ai')).calls, 2);
    assert.equal((await api(`/api/documents/${failedId}`)).status, 'UPLOADED');
    await page.getByRole('button', { name: 'Try analysis again', exact: true }).waitFor({ timeout: 35000 });
    await page.getByRole('button', { name: 'Try analysis again', exact: true }).click();
    assert.equal((await api('/api/test/receipt-ai')).calls, 2);
    await page.getByRole('button', { name: 'Confirm another analysis attempt', exact: true }).click(); await page.getByRole('alert').filter({ hasText: "couldn't analyze" }).waitFor();
    assert.equal((await api('/api/test/receipt-ai')).calls, 3);
    await page.getByRole('button', { name: 'Enter details manually', exact: true }).click(); await page.getByLabel('Simple summary', { exact: true }).fill('Manually checked details');
    await page.getByRole('button', { name: 'Confirm document details', exact: true }).click(); await page.getByRole('heading', { name: 'Confirmed by you', exact: true }).waitFor();
    assert.equal((await api(`/api/documents/${failedId}/analysis`)).source, 'MANUAL'); assert.equal((await api('/api/test/receipt-ai')).calls, 3);
    assert.deepEqual(errors, []); assert.deepEqual(external, []);
  } finally { await context.close(); await browser.close(); }
});
