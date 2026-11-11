import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFile, mkdir } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { chromium } from 'playwright-core';

// Use a running local dev backend/frontend. Every test creates its own accounts and
// receipts; it never edits/deletes a pre-existing user's data or invokes real AI.
const base = process.env.LIFE_ADMIN_TEST_URL || 'http://localhost:3001';
const year = new Date().getUTCFullYear();
const artifacts = process.env.LIFE_ADMIN_TEST_ARTIFACTS || '/tmp/life-admin-m15-browser';
let browser;
before(async () => {
  assert.ok(['localhost', '127.0.0.1'].includes(new URL(base).hostname), 'Use a local test instance');
  await mkdir(artifacts, { recursive: true });
  browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: process.env.LIFE_ADMIN_TEST_HEADED !== '1' });
});
after(async () => { await browser?.close(); });
async function workspace() {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, acceptDownloads: true });
  const page = await context.newPage(), errors = [], external = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('request', r => { if (/api\.openai\.com|\/analyze(?:\?|$)/.test(r.url())) external.push(r.url()); });
  const email = `m13-${randomUUID()}@example.test`, password = `Fixture-${randomUUID()}`;
  await page.goto(`${base}/register`);
  await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByLabel('Confirm password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Create account', exact: true }).click();
  await page.waitForURL(`${base}/`);
  const capabilities = await api(context, '/api/receipts/extraction-capabilities');
  assert.equal(capabilities.imageExtractionEnabled, false, 'Regression tests require a backend with OPENAI_API_KEY unset; never spend credits');
  return { context, page, email, password, errors, external };
}
async function api(context, path) { const result = await context.request.get(base + path); assert.equal(result.status(), 200); return result.json(); }
async function mutate(context, path, method, data) {
  const csrf = await api(context, '/api/auth/csrf');
  return context.request.fetch(base + path, { method, headers: { [csrf.headerName]: csrf.token }, data });
}
async function upload(page, filename, mimeType, buffer) {
  await page.goto(`${base}/receipts`);
  await page.getByRole('button', { name: '+ Add receipt', exact: true }).click();
  await page.getByLabel('Receipt file', { exact: true }).setInputFiles({ name: filename, mimeType, buffer });
  const uploaded = page.waitForResponse(r => new URL(r.url()).pathname === '/api/receipts' && r.request().method() === 'POST');
  await page.getByRole('button', { name: 'Upload receipt', exact: true }).click();
  const result = await uploaded; assert.equal(result.status(), 201, await result.text());
  await page.waitForURL(/\/receipts\/[a-f0-9-]{36}$/);
  await page.getByRole('heading', { name: 'Review receipt details', exact: true }).waitFor();
  return page.url().split('/').at(-1);
}
async function saved(page) {
  await page.getByRole('button', { name: 'Save receipt', exact: true }).click();
  await page.getByText('Receipt saved and reviewed.', { exact: true }).waitFor();
  await page.getByText('Purchase record · Reviewed', { exact: true }).waitFor();
}

test('PDF upload → real text extraction → corrections → review → tax totals and CSV → login persistence', { timeout: 120000 }, async () => {
  const { context, page, email, password, errors, external } = await workspace();
  try {
    const bytes = await readFile(new URL('../../docs/fixtures/receipt-text.pdf', import.meta.url));
    const id = await upload(page, 'review-fixture.pdf', 'application/pdf', bytes);
    assert.equal((await api(context, `/api/receipts/${id}`)).reviewStatus, 'NEEDS_REVIEW');
    assert.equal(await page.getByLabel('Merchant', { exact: true }).inputValue(), 'Cedar Market');
    assert.equal(await page.locator('object[aria-label="Receipt PDF preview"]').getAttribute('data'), `/api/receipts/${id}/content`);
    assert.deepEqual(await (await context.request.get(`${base}/api/receipts/${id}/content`)).body(), bytes);
    await page.getByRole('heading', { name: 'Suggested by Life Admin', exact: true }).waitFor();
    assert.equal((await api(context, `/api/receipts/${id}`)).extractionMethod, 'PDF_TEXT');
    assert.equal(await page.getByRole('button', { name: 'Load sample extracted details', exact: true }).count(), 0);
    assert.equal(await page.getByLabel('Merchant', { exact: true }).inputValue(), 'Cedar Market');
    assert.equal(await page.getByLabel('Purchase date', { exact: true }).inputValue(), '2025-09-20');
    assert.equal(await page.getByLabel('Amount', { exact: true }).inputValue(), '6.72');
    assert.equal(await page.getByLabel('Category', { exact: true }).inputValue(), 'MEALS');
    assert.equal((await api(context, `/api/receipts/${id}`)).reviewStatus, 'NEEDS_REVIEW');
    await page.reload();
    await page.getByRole('heading', { name: 'Review receipt details', exact: true }).waitFor();
    assert.equal(await page.getByLabel('Merchant', { exact: true }).inputValue(), 'Cedar Market');
    const draft = await api(context, `/api/receipts/tax?year=${year}`);
    assert.equal(draft.summary.receiptCount, 0);
    assert.ok(draft.recordsNeedingReview.some(r => r.id === id && r.reasons.includes('Receipt details need your review')));
    await page.getByLabel('Merchant', { exact: true }).fill('Reviewed café, "Shop"');
    await page.getByLabel('Purchase date', { exact: true }).fill('2025-05-04');
    await page.getByLabel('Amount', { exact: true }).fill('8.43');
    await page.getByLabel('Currency', { exact: true }).selectOption('USD');
    await page.getByLabel('Category', { exact: true }).selectOption('OFFICE');
    await page.getByLabel('Description (optional)', { exact: true }).fill('Corrected office purchase');
    await page.getByLabel('Potentially tax relevant', { exact: true }).selectOption('yes');
    await page.getByLabel('Notes (optional)', { exact: true }).fill('=SUM(1,2)');
    await saved(page);
    await page.getByRole('heading', { name: 'Confirmed by you', exact: true }).waitFor();
    assert.equal((await mutate(context, `/api/receipts/${id}/extract`, 'POST')).status(), 409);
    assert.equal(await page.getByRole('button', { name: 'Load sample extracted details', exact: true }).count(), 0);
    await page.reload();
    await page.getByText('Purchase record · Reviewed', { exact: true }).waitFor();
    assert.equal(await page.getByLabel('Merchant', { exact: true }).inputValue(), 'Reviewed café, "Shop"');
    assert.equal(await page.getByLabel('Amount', { exact: true }).inputValue(), '8.43');
    if (process.env.LIFE_ADMIN_TEST_HEADED === '1') {
      await page.waitForTimeout(2000);
      assert.ok(page.frames().some(f => f.url().startsWith('chrome-extension://')), 'Native PDF viewer loaded');
    }
    await page.screenshot({ path: `${artifacts}/review-desktop.png`, fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.screenshot({ path: `${artifacts}/review-mobile.png`, fullPage: true });
    await page.setViewportSize({ width: 1440, height: 1000 });
    // Insights use the saved values through the real isolated backend, not suggestions.
    await page.goto(`${base}/receipts/insights`);
    await page.locator('.receipt-summary strong').first().waitFor();
    await page.getByLabel('Year', { exact: true }).selectOption('2025');
    await page.waitForFunction(() => document.querySelector('.receipt-summary strong')?.textContent === '$8.43');
    assert.deepEqual(await page.locator('.receipt-summary strong').allTextContents(), ['$8.43', '1', '$8.43']);
    await page.getByRole('table', { name: 'Spending by merchant (USD)', exact: true }).getByText('Reviewed café, "Shop"', { exact: true }).waitFor();
    assert.equal(await page.getByRole('table', { name: 'Monthly totals (USD)', exact: true }).locator('tbody tr').count(), 12);
    await page.goto(`${base}/receipts/tax`);
    await page.getByLabel('Tax year', { exact: true }).fill('2025');
    await page.getByRole('button', { name: 'Review year', exact: true }).click();
    await page.getByRole('heading', { name: 'Category totals — 2025', exact: true }).waitFor();
    assert.deepEqual(await page.locator('.tax-summary strong').allTextContents(), ['1', '$8.43', '1', '$8.43']);
    await page.getByRole('row').filter({ hasText: 'Office / Supplies' }).waitFor();
    assert.equal(await page.locator('.tax-review-list li').count(), 0);
    for (const scope of ['all', 'tax-relevant']) {
      await page.getByLabel('Receipts to export', { exact: true }).selectOption(scope);
      const pending = page.waitForEvent('download');
      await page.getByRole('button', { name: 'Export CSV', exact: true }).click();
      const download = await pending;
      assert.equal(download.suggestedFilename(), 'life-admin-receipts-2025.csv');
      const csv = await readFile(await download.path(), 'utf8');
      assert.ok(csv.includes('"2025-05-04","Reviewed café, ""Shop""","8.43","USD","Office / Supplies","Corrected office purchase","Yes","\'=SUM(1,2)"'));
      assert.ok(!csv.includes('Cedar Market') && !csv.includes(id));
    }
    await page.locator('.tax-receipt-row').click();
    await page.waitForURL(`${base}/receipts/${id}`);
    await page.getByLabel('Amount', { exact: true }).fill('9.10');
    await page.getByLabel('Category', { exact: true }).selectOption('MEDICAL');
    await page.getByLabel('Purchase date', { exact: true }).fill('2024-12-31');
    await saved(page);
    assert.equal((await api(context, '/api/receipts/tax?year=2025')).summary.receiptCount, 0);
    const updated = (await api(context, '/api/receipts/tax?year=2024')).summary;
    assert.equal(updated.totalAmount, '9.10');
    assert.equal((await api(context, '/api/receipts/insights?year=2025')).receiptCount, 0);
    const insights = await api(context, '/api/receipts/insights?year=2024');
    assert.equal(insights.totalAmount, '9.10');
    assert.equal(insights.categories[0].name, 'MEDICAL');
    assert.equal(insights.months[11].amount, '9.10');
    assert.deepEqual(updated.categories, [{ category: 'MEDICAL', count: 1, amount: '9.10' }]);
    await page.getByRole('button', { name: 'Log out', exact: true }).click();
    await page.waitForURL(`${base}/login`);
    await page.getByLabel('Email', { exact: true }).fill(email);
    await page.getByLabel('Password', { exact: true }).fill(password);
    await page.getByRole('button', { name: 'Log in', exact: true }).click();
    await page.waitForURL(`${base}/`);
    await page.goto(`${base}/receipts/${id}`);
    await page.getByText('Purchase record · Reviewed', { exact: true }).waitFor();
    assert.equal(await page.getByLabel('Amount', { exact: true }).inputValue(), '9.10');
    assert.deepEqual(errors, []); assert.deepEqual(external, []);
  } finally { await context.close(); }
});

test('image preview, manual review, validation and failed saves preserve editable values', { timeout: 90000 }, async () => {
  const { context, page, errors, external } = await workspace();
  try {
    const image = await page.screenshot({ clip: { x: 0, y: 0, width: 200, height: 200 } });
    const id = await upload(page, 'manual-receipt.png', 'image/png', image);
    await page.waitForFunction(() => { const img = document.querySelector('.receipt-preview img'); return img?.complete && img.naturalWidth > 0; });
    for (const label of ['Merchant', 'Purchase date', 'Amount', 'Currency', 'Category', 'Description (optional)']) assert.equal(await page.getByLabel(label, { exact: true }).inputValue(), '');
    await page.getByRole('note').filter({ hasText: 'Automatic photo reading is not configured' }).waitFor();
    await page.getByRole('button', { name: 'Save receipt', exact: true }).click();
    assert.equal(await page.getByLabel('Merchant', { exact: true }).evaluate(el => el.validity.valueMissing), true);
    assert.equal((await api(context, `/api/receipts/${id}`)).reviewStatus, 'NEEDS_REVIEW');
    await page.getByLabel('Merchant', { exact: true }).fill('Manual shop');
    await page.getByLabel('Purchase date', { exact: true }).fill('2025-01-02');
    await page.getByLabel('Amount', { exact: true }).fill('0');
    await page.getByRole('button', { name: 'Save receipt', exact: true }).click();
    assert.equal(await page.getByLabel('Amount', { exact: true }).evaluate(el => el.validity.rangeUnderflow), true);
    await page.getByLabel('Amount', { exact: true }).fill('2.50');
    assert.equal(await page.getByLabel('Currency', { exact: true }).inputValue(), '');
    await page.getByLabel('Currency', { exact: true }).selectOption('USD');
    await page.getByLabel('Category', { exact: true }).selectOption('OTHER');
    await page.route(`**/api/receipts/${id}`, async route => {
      if (route.request().method() === 'PUT') await route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ message: 'The receipt file is unavailable or invalid. Check the original file before saving.' }) });
      else await route.continue();
    });
    await page.getByRole('button', { name: 'Save receipt', exact: true }).click();
    await page.getByRole('alert').filter({ hasText: 'receipt file is unavailable' }).waitFor();
    assert.equal(await page.getByLabel('Merchant', { exact: true }).inputValue(), 'Manual shop');
    assert.equal((await api(context, `/api/receipts/${id}`)).reviewStatus, 'NEEDS_REVIEW');
    await page.unroute(`**/api/receipts/${id}`);
    await saved(page);
    assert.equal((await api(context, `/api/receipts/${id}`)).extractionStatus, 'OCR_REQUIRED');
    assert.equal((await api(context, `/api/receipts/${id}`)).suggestedMerchant, null);
    assert.equal(await page.getByText('DEVELOPMENT SAMPLE', { exact: false }).count(), 0);
    await page.screenshot({ path: `${artifacts}/image-review.png`, fullPage: true });
    assert.deepEqual(errors, []); assert.deepEqual(external, []);
  } finally { await context.close(); }
});

test('other accounts cannot view, extract or review a receipt; missing IDs stay safe', { timeout: 90000 }, async () => {
  const a = await workspace(), b = await workspace();
  try {
    const id = await upload(a.page, 'private.pdf', 'application/pdf', await readFile(new URL('../../docs/fixtures/TestRecord.pdf', import.meta.url)));
    await b.page.goto(`${base}/receipts/${id}`);
    await b.page.getByRole('alert').filter({ hasText: 'Receipt not found' }).waitFor();
    assert.equal(await b.page.getByRole('button', { name: 'Save receipt', exact: true }).count(), 0);
    for (const suffix of ['', '/content', '/extraction']) assert.equal((await b.context.request.get(`${base}/api/receipts/${id}${suffix}`)).status(), 404);
    assert.equal((await mutate(b.context, `/api/receipts/${id}/extract`, 'POST')).status(), 404);
    assert.equal((await mutate(b.context, `/api/receipts/${id}`, 'PUT', { merchant: 'Forged', purchaseDate: '2025-01-01', amount: '1', currency: 'USD', taxRelevant: false })).status(), 404);
    assert.equal((await a.context.request.post(`${base}/api/receipts/${id}/extract`)).status(), 403);
    await b.page.goto(`${base}/receipts/${randomUUID()}`);
    await b.page.getByRole('alert').filter({ hasText: 'Receipt not found' }).waitFor();
    const original = await api(a.context, `/api/receipts/${id}`);
    assert.equal(original.reviewStatus, 'NEEDS_REVIEW');assert.equal(original.merchant, null);
    assert.deepEqual(a.errors.concat(b.errors), []);assert.deepEqual(a.external.concat(b.external), []);
  } finally { await a.context.close(); await b.context.close(); }
});


test('uncertain text and scanned PDFs leave all unknown fields blank with honest reasons', { timeout: 90000 }, async () => {
  const { context, page, errors, external } = await workspace();
  try {
    for (const [name, status] of [['receipt-uncertain.pdf', 'NO_MATCH'], ['receipt-scanned.pdf', 'OCR_REQUIRED']]) {
      const id = await upload(page, name, 'application/pdf', await readFile(new URL(`../../docs/fixtures/${name}`, import.meta.url)));
      const record = await api(context, `/api/receipts/${id}`);
      assert.equal(record.extractionStatus, status);assert.equal(record.reviewStatus, 'NEEDS_REVIEW');
      for (const label of ['Merchant', 'Purchase date', 'Amount', 'Currency', 'Category', 'Description (optional)']) assert.equal(await page.getByLabel(label, { exact: true }).inputValue(), '');
      assert.equal(await page.getByText('Could not determine automatically.', { exact: true }).count(), 6);
      assert.equal(await page.getByRole('button', { name: 'Load sample extracted details', exact: true }).count(), 0);
      assert.equal(record.suggestedAmount, null);assert.equal(record.suggestedPurchaseDate, null);assert.equal(record.suggestedMerchant, null);
      await page.reload();await page.getByRole('heading', { name: 'Review receipt details', exact: true }).waitFor();
      assert.equal(await page.getByLabel('Currency', { exact: true }).inputValue(), '');
      await page.screenshot({ path: `${artifacts}/${name}.png`, fullPage: true });
    }
    assert.deepEqual(errors, []);assert.deepEqual(external, []);
  } finally { await context.close(); }
});
