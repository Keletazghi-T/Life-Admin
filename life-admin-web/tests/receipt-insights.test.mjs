import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chromium } from 'playwright-core';

// Every API request is intercepted. No account data or extraction service is used.
const base = process.env.LIFE_ADMIN_TEST_URL || 'http://localhost:3001';
const current = new Date().getUTCFullYear();
for (const width of [1440, 390]) {
  test(`Spending Insights: navigation, year totals, empty state, retry and layout at ${width}px`, async () => {
    const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true });
    const context = await browser.newContext({ viewport: { width, height: 900 }, serviceWorkers: 'block' });
    const requests = [], unexpected = [], errors = [];
    let fail = false;
    await context.route('**/*', async route => {
      const url = new URL(route.request().url());
      if (url.origin !== base) { unexpected.push(url.origin); return route.abort(); }
      if (!url.pathname.startsWith('/api/')) return route.continue();
      if (route.request().method() !== 'GET') { unexpected.push('mutation'); return route.abort(); }
      if (url.pathname === '/api/auth/me') return route.fulfill({ json: { id: 'insights-fixture', email: 'fixture@example.test' } });
      if (url.pathname === '/api/receipts') return route.fulfill({ json: { items: [], hasMore: false } });
      if (url.pathname === '/api/receipts/summary') return route.fulfill({ json: { year: current, currency: 'USD', receiptCount: 0, totalAmount: '0.00', taxRelevantCount: 0, taxRelevantAmount: '0.00', categories: [] } });
      if (url.pathname === '/api/receipts/insights') {
        requests.push(url.searchParams.get('year'));
        if (fail) return route.fulfill({ status: 503, json: { message: 'Test unavailable' } });
        const year = Number(url.searchParams.get('year')), empty = year === current - 2;
        return route.fulfill({ json: {
          year, currency: 'USD', years: [current, current - 1, current - 2], receiptCount: empty ? 0 : 3,
          totalAmount: empty ? '0.00' : year === current ? '30.00' : '60.00', averageAmount: empty ? '0.00' : year === current ? '10.00' : '20.00',
          categories: empty ? [] : [{ name: 'MEALS', count: 2, amount: '20.00' }, { name: null, count: 1, amount: '10.00' }],
          merchants: empty ? [] : [{ name: 'Cafe', count: 2, amount: '20.00' }, { name: 'Shop', count: 1, amount: '10.00' }],
          months: Array.from({ length: 12 }, (_, index) => ({ month: index + 1, amount: !empty && index === 0 ? '30.00' : '0.00' })),
        } });
      }
      unexpected.push(url.pathname); return route.abort();
    });
    try {
      const page = await context.newPage(); page.on('pageerror', e => errors.push(e.message));
      await page.goto(base + '/receipts');
      await page.getByRole('link', { name: 'View spending insights', exact: true }).click();
      await page.waitForURL(base + '/receipts/insights');
      await page.locator('.receipt-summary strong').first().waitFor();
      assert.equal(requests[0], String(current));
      assert.equal(await page.getByLabel('Year', { exact: true }).inputValue(), String(current));
      assert.deepEqual(await page.locator('.receipt-summary strong').allTextContents(), ['$30.00', '3', '$10.00']);
      const category = page.getByRole('table', { name: 'Spending by category (USD)', exact: true });
      assert.match(await category.innerText(), /Meals/); assert.match(await category.innerText(), /Uncategorized/);
      const merchants = page.getByRole('table', { name: 'Spending by merchant (USD)', exact: true });
      assert.match(await merchants.locator('tbody tr').first().innerText(), /Cafe.*2.*\$20\.00/s);
      const monthly = page.getByRole('table', { name: 'Monthly totals (USD)', exact: true });
      assert.equal(await monthly.locator('tbody tr').count(), 12); assert.match(await monthly.locator('tbody tr').last().innerText(), /Dec.*\$0\.00/s);
      await page.getByLabel('Year', { exact: true }).selectOption(String(current - 1));
      await page.waitForFunction(() => document.querySelector('.receipt-summary strong')?.textContent === '$60.00');
      await page.getByLabel('Year', { exact: true }).selectOption(String(current - 2));
      await page.getByText(`No reviewed receipts with confirmed amounts for ${current - 2}.`, { exact: true }).waitFor();
      assert.deepEqual(await page.locator('.receipt-summary strong').allTextContents(), ['$0.00', '0', '$0.00']);
      assert.equal(await monthly.locator('tbody tr').count(), 12);
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      fail = true;
      await page.getByLabel('Year', { exact: true }).selectOption(String(current));
      await page.getByRole('alert').waitFor(); assert.equal(await page.locator('.receipt-summary').count(), 0);
      fail = false; await page.getByRole('button', { name: 'Try again', exact: true }).click();
      await page.locator('.receipt-summary strong').first().waitFor();
      await page.reload(); await page.locator('.receipt-summary strong').first().waitFor();
      assert.deepEqual(unexpected, []); assert.deepEqual(errors, []);
    } finally { await context.close(); await browser.close(); }
  });
}
