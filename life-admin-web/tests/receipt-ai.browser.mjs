import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { chromium } from 'playwright-core';

// Requires the test-classpath-only receiptAiBrowser backend; refuses ordinary servers.
const base = 'http://localhost:3002';
const artifacts = '/tmp/life-admin-m16-browser';
test('mocked Responses HTTP → photo suggestions → correction → review → login → isolation → deletion; HTTP failure remains editable', { timeout: 120000 }, async () => {
  await mkdir(artifacts, { recursive: true });
  const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1050 } });
  const page = await context.newPage(); const errors = []; const external = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('request', request => { if (request.url().includes('api.openai.com')) external.push(request.url()); });
  const email = `m16-${randomUUID()}@example.test`, password = `Fixture-${randomUUID()}`;
  async function api(path) { const r = await context.request.get(base + path); assert.equal(r.status(), 200); return r.json(); }
  async function mutate(path, method, data) { const csrf = await api('/api/auth/csrf'); return context.request.fetch(base + path, { method, headers: { [csrf.headerName]: csrf.token }, data }); }
  async function upload(type, bytes) {
    await page.goto(base + '/receipts'); await page.getByRole('button', { name: '+ Add receipt', exact: true }).click();
    await page.getByLabel('Receipt file', { exact: true }).setInputFiles({ name: `browser-receipt.${type}`, mimeType: type === 'png' ? 'image/png' : 'image/jpeg', buffer: bytes });
    const response = page.waitForResponse(r => new URL(r.url()).pathname === '/api/receipts' && r.request().method() === 'POST');
    await page.getByRole('button', { name: 'Upload receipt', exact: true }).click();
    const r = await response; assert.equal(r.status(), 201); const receipt = await r.json();
    await page.waitForURL(base + '/receipts/' + receipt.id); await page.getByRole('heading', { name: 'Review receipt details', exact: true }).waitFor();
    return receipt;
  }
  try {
    // Generate harmless image bytes; expectations below are explicitly mocked, not real-AI evidence.
    await page.setContent('<div style="width:380px;background:white;color:black;padding:30px;font:24px monospace"><h2>Oak Cafe</h2><p>September 20, 2025</p><p>Coffee 6.00<br>Tax 0.72</p><strong>TOTAL USD 6.72</strong><p>TEST RECEIPT</p></div>');
    const png = await page.locator('div').screenshot({ type: 'png' }); const jpg = await page.locator('div').screenshot({ type: 'jpeg' });
    await page.goto(base + '/register'); await page.getByLabel('Email', { exact: true }).fill(email); await page.getByLabel('Password', { exact: true }).fill(password); await page.getByLabel('Confirm password', { exact: true }).fill(password);
    await page.getByRole('button', { name: 'Create account', exact: true }).click(); await page.waitForURL(base + '/');
    const mode = await api('/api/test/receipt-ai'); assert.equal(mode.mocked, true, 'Must use isolated mocked HTTP harness'); const initialCalls = mode.calls;
    const receipt = await upload('png', png); const id = receipt.id;
    assert.equal(receipt.merchant, null); assert.equal(receipt.amount, null); assert.equal(receipt.suggestedMerchant, 'Oak Cafe'); assert.equal(receipt.suggestedAmount, '6.72');
    assert.equal(receipt.description, null); assert.equal(receipt.suggestedDescription, 'Coffee'); assert.equal(receipt.taxRelevant, false);
    assert.equal(receipt.extractionMethod, 'OPENAI_IMAGE'); assert.equal(receipt.extractionModel, 'browser-mocked-model'); assert.equal(receipt.reviewStatus, 'NEEDS_REVIEW');
    await page.waitForFunction(() => { const img = document.querySelector('.receipt-preview img'); return img?.complete && img.naturalWidth > 0; });
    assert.equal(await page.getByLabel('Merchant', { exact: true }).inputValue(), 'Oak Cafe'); assert.equal(await page.getByLabel('Purchase date', { exact: true }).inputValue(), '2025-09-20'); assert.equal(await page.getByLabel('Amount', { exact: true }).inputValue(), '6.72');
    await page.getByRole('heading', { name: 'Suggested by Life Admin', exact: true }).waitFor(); await page.screenshot({ path: `${artifacts}/photo-suggestions.png`, fullPage: true });
    assert.equal(await page.getByLabel('Description (optional)', { exact: true }).inputValue(), 'Coffee');
    await page.getByLabel('Description (optional)', { exact: true }).fill('Coffee reviewed by me');
    await page.getByLabel('Potentially tax relevant', { exact: true }).selectOption('yes');
    await page.getByLabel('Merchant', { exact: true }).fill('Oak Cafe corrected'); await page.getByLabel('Amount', { exact: true }).fill('7.25');
    await page.getByRole('button', { name: 'Save receipt', exact: true }).click(); await page.getByText('Receipt saved and reviewed.', { exact: true }).waitFor();
    await page.getByRole('heading', { name: 'Confirmed by you', exact: true }).waitFor();
    assert.equal((await mutate(`/api/receipts/${id}/extract`, 'POST')).status(), 409); assert.equal((await api('/api/test/receipt-ai')).calls, initialCalls + 1);
    await page.reload(); assert.equal(await page.getByLabel('Amount', { exact: true }).inputValue(), '7.25');
    await page.getByRole('button', { name: 'Log out', exact: true }).click(); await page.waitForURL(base + '/login'); await page.getByLabel('Email', { exact: true }).fill(email); await page.getByLabel('Password', { exact: true }).fill(password); await page.getByRole('button', { name: 'Log in', exact: true }).click(); await page.waitForURL(base + '/');
    await page.goto(base + '/receipts/' + id); assert.equal(await page.getByLabel('Merchant', { exact: true }).inputValue(), 'Oak Cafe corrected');
    assert.equal(await page.getByLabel('Description (optional)', { exact: true }).inputValue(), 'Coffee reviewed by me');
    assert.equal(await page.getByLabel('Potentially tax relevant', { exact: true }).inputValue(), 'yes');
    const persisted = await api(`/api/receipts/${id}`); assert.equal(persisted.suggestedDescription, 'Coffee'); assert.equal(persisted.description, 'Coffee reviewed by me');
    const other = await browser.newContext(); const csrfResult = await other.request.get(base + '/api/auth/csrf'); const csrf = await csrfResult.json();
    const registered = await other.request.post(base + '/api/auth/register', { headers: { [csrf.headerName]: csrf.token }, data: { email: `other-${randomUUID()}@example.test`, password, confirmPassword: password } }); assert.equal(registered.status(), 201);
    assert.equal((await other.request.get(base + `/api/receipts/${id}/content`)).status(), 404); await other.close();
    assert.equal((await mutate(`/api/receipts/${id}`, 'DELETE')).status(), 204); assert.equal((await context.request.get(base + `/api/receipts/${id}/content`)).status(), 404);
    assert.equal((await mutate('/api/test/receipt-ai/fail', 'POST')).status(), 204);
    const failed = await upload('jpg', jpg); assert.equal(failed.extractionStatus, 'EXTRACTION_UNAVAILABLE'); assert.equal(failed.reviewStatus, 'NEEDS_REVIEW');
    assert.equal(failed.suggestedAmount, null); assert.equal(failed.suggestedMerchant, null);
    await page.getByRole('note').filter({ hasText: "We couldn't automatically read this receipt. Please enter the details manually." }).waitFor();
    for (const field of ['Merchant', 'Purchase date', 'Amount', 'Currency', 'Category', 'Description (optional)']) assert.equal(await page.getByLabel(field, { exact: true }).inputValue(), '');
    await page.getByLabel('Merchant', { exact: true }).fill('Manual cafe'); await page.getByLabel('Purchase date', { exact: true }).fill('2025-09-20'); await page.getByLabel('Amount', { exact: true }).fill('6.72'); await page.getByLabel('Currency', { exact: true }).selectOption('USD');
    await page.screenshot({ path: `${artifacts}/photo-failure-manual.png`, fullPage: true });
    await page.getByRole('button', { name: 'Save receipt', exact: true }).click(); await page.getByText('Receipt saved and reviewed.', { exact: true }).waitFor();
    assert.equal((await api('/api/test/receipt-ai')).calls, initialCalls + 2); assert.deepEqual(errors, []); assert.deepEqual(external, []);
  } finally { await context.close(); await browser.close(); }
});
