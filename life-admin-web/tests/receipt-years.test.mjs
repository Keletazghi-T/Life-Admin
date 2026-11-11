import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chromium } from 'playwright-core';

// All API calls are intercepted: no live account, database, uploads or extraction.
const base = process.env.LIFE_ADMIN_TEST_URL || 'http://localhost:3001';
for (const year of ['', '2025', '2026']) {
  test(`Receipts year filter: ${year || 'All years'} retains the appropriate reviewed and unreviewed rows`, async () => {
    const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true });
    const context = await browser.newContext({ serviceWorkers: 'block' });
    const requests = [], unexpected = [], errors = [];
    const receipts = [
      { id: 'reviewed-2025', merchant: 'Oak Cafe', purchaseDate: '2025-09-20', amount: '6.72', reviewStatus: 'REVIEWED' },
      { id: 'reviewed-2026', merchant: 'Current shop', purchaseDate: '2026-09-20', amount: '6.72', reviewStatus: 'REVIEWED' },
      { id: 'unreviewed-2026', merchant: null, purchaseDate: null, amount: null, reviewStatus: 'NEEDS_REVIEW' },
    ].map(r => ({ ...r, originalFileName: 'Unreviewed photo.png', createdAt: '2026-09-22T12:00:00Z', currency: 'USD', category: null, status: 'SAVED', taxRelevant: false }));
    await context.route('**/*', async route => {
      const url = new URL(route.request().url());
      if (url.origin !== base) { unexpected.push(url.origin); return route.abort(); }
      if (!url.pathname.startsWith('/api/')) return route.continue();
      if (route.request().method() !== 'GET') { unexpected.push('mutation'); return route.abort(); }
      if (url.pathname === '/api/auth/me') return route.fulfill({ json: { id: 'fixture', email: 'fixture@example.test' } });
      if (url.pathname === '/api/receipts/summary') return route.fulfill({ json: { year: Number(url.searchParams.get('year')), currency: 'USD', receiptCount: 1, totalAmount: '6.72', taxRelevantCount: 0, taxRelevantAmount: '0.00', missingCategoryCount: 0, missingInformationCount: 0, categories: [] } });
      if (url.pathname === '/api/receipts') {
        requests.push(url.searchParams);
        const selected = url.searchParams.get('year');
        const items = receipts.filter(r => !selected || (r.purchaseDate || r.createdAt).startsWith(selected));
        return route.fulfill({ json: { items, hasMore: url.searchParams.get('search') === 'paging' && url.searchParams.get('page') === '0' } });
      }
      unexpected.push(url.pathname); return route.abort();
    });
    try {
      const page = await context.newPage(); page.on('pageerror', e => errors.push(e.message));
      await page.goto(base + '/receipts'); await page.locator('.receipt-row').first().waitFor();
      assert.equal(await page.getByLabel('Year', { exact: true }).inputValue(), '');
      assert.equal(requests[0].has('year'), false);
      assert.equal(await page.getByLabel('Year', { exact: true }).locator('option:checked').textContent(), 'All years');
      if (year) {
        await page.getByLabel('Year', { exact: true }).selectOption(year);
        const response = page.waitForResponse(r => new URL(r.url()).pathname === '/api/receipts' && new URL(r.url()).searchParams.get('year') === year);
        await page.getByRole('button', { name: 'Apply filters', exact: true }).click(); await response;
        await page.locator('.receipt-row').first().waitFor();
      }
      assert.equal(await page.locator('.receipt-row').count(), year === '2025' ? 1 : year === '2026' ? 2 : 3);
      assert.equal(await page.locator('.receipt-row').filter({ hasText: 'Oak Cafe' }).count(), year === '2026' ? 0 : 1);
      assert.equal(await page.locator('.receipt-row').filter({ hasText: 'Needs review' }).count(), year === '2025' ? 0 : 1);
      if (!year) {
        await page.getByLabel('Search receipts').fill('paging'); await page.getByLabel('Filter by category').selectOption('MEALS'); await page.getByLabel('Sort receipts').selectOption('amount-high');
        await page.getByRole('button', { name: 'Apply filters', exact: true }).click(); await page.getByRole('button', { name: 'Next receipts' }).waitFor();
        assert.equal(requests.at(-1).get('search'), 'paging'); assert.equal(requests.at(-1).get('category'), 'MEALS'); assert.equal(requests.at(-1).get('sort'), 'amount-high');
        await page.getByRole('button', { name: 'Next receipts' }).click(); await page.getByRole('button', { name: 'Previous' }).waitFor();
        assert.equal(requests.at(-1).get('page'), '1'); assert.equal(requests.at(-1).has('year'), false);
      }
      assert.deepEqual(unexpected, []); assert.deepEqual(errors, []);
    } finally { await context.close(); await browser.close(); }
  });
}
