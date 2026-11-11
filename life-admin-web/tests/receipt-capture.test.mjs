import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { chromium } from 'playwright-core';

const base = process.env.LIFE_ADMIN_TEST_URL || 'http://localhost:3001';
for (const mode of ['jpeg-success', 'png-failure-retry']) {
 test(`Receipt capture: ${mode}, duplicate guards, refresh and mobile review`, { timeout: 60000 }, async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true });
  const context = await browser.newContext({ viewport: { width: mode.startsWith('png') ? 390 : 1440, height: 1000 }, serviceWorkers: 'block' });
  const page = await context.newPage(); const unexpected = [], errors = [];
  let uploads = 0, extractions = 0, uploadToken, retryVersion, release;
  const waiting = new Promise(resolve => { release = resolve; });
  const bytes = await readFile(new URL('../../docs/fixtures/receipt-photo.png', import.meta.url));
  const id='00000000-0000-4000-8000-000000000017';
  let receipt = { id, originalFileName: mode.startsWith('jpeg') ? 'capture.jpg' : 'capture.png', contentType: mode.startsWith('jpeg') ? 'image/jpeg' : 'image/png', fileSize: bytes.length, merchant: null, purchaseDate: null, amount: null, currency: 'USD', category: null, description: '', notes: '', taxRelevant: false, status: 'SAVED', reviewStatus: 'NEEDS_REVIEW', createdAt: '2026-09-22T12:00:00Z', updatedAt: '2026-09-22T12:00:01Z', extractionStatus: mode.endsWith('success') ? 'EXTRACTED' : 'EXTRACTION_UNAVAILABLE', extractionMethod: 'OPENAI_IMAGE', extractionError: mode.endsWith('success') ? null : "We couldn't automatically read this receipt. Please enter the details manually.", suggestedMerchant: mode.endsWith('success') ? 'Oak Cafe' : null, suggestedPurchaseDate: mode.endsWith('success') ? '2025-09-20' : null, suggestedAmount: mode.endsWith('success') ? '6.72' : null, suggestedCurrency: mode.endsWith('success') ? 'USD' : null, suggestedCategory: mode.endsWith('success') ? 'MEALS' : null, suggestedDescription: mode.endsWith('success') ? 'Coffee' : null };
  await context.route('**/*', async route => {
   const url = new URL(route.request().url()), method = route.request().method();
   if (url.origin !== base) { unexpected.push(url.origin); return route.abort(); }
   if (!url.pathname.startsWith('/api/')) return route.continue();
   const json = data => route.fulfill({ json: data });
   if(url.pathname==='/api/auth/me')return json({id:'capture-fixture',email:'fixture@example.test'});
   if(url.pathname==='/api/auth/csrf')return json({headerName:'X-CSRF-TOKEN',token:'mock-token'});
   if(url.pathname==='/api/receipts/summary')return json({year:2026,receiptCount:0,totalAmount:'0',taxRelevantCount:0,taxRelevantAmount:'0',categories:[]});
   if(url.pathname==='/api/receipts' && method==='GET')return json({items:[],hasMore:false});
   if(url.pathname==='/api/receipts' && method==='POST') {uploads++;uploadToken=route.request().headers()['idempotency-key'];await waiting;return route.fulfill({status:201,json:receipt});}
   if(url.pathname===`/api/receipts/${id}/content`)return route.fulfill({contentType:'image/png',body:bytes});
   if(url.pathname===`/api/receipts/${id}` && method==='GET')return json(receipt);
   if(url.pathname===`/api/receipts/${id}` && method==='PUT'){receipt={...receipt,...route.request().postDataJSON(),reviewStatus:'REVIEWED',updatedAt:'2026-09-22T12:02:00Z'};return json(receipt);}
   if(url.pathname===`/api/receipts/${id}/extract` && method==='POST'){extractions++;retryVersion=route.request().headers()['if-match'];receipt={...receipt,suggestedMerchant:'Oak Cafe',suggestedPurchaseDate:'2025-09-20',suggestedAmount:'6.72',suggestedCurrency:'USD',suggestedCategory:'MEALS',suggestedDescription:'Coffee',extractionStatus:'EXTRACTED',extractionError:null,updatedAt:'2026-09-22T12:01:00Z'};return json({});}
   unexpected.push(`${method} ${url.pathname}`);return route.abort();
  });
  page.on('pageerror',e=>errors.push(e.message));
  try {
   await page.goto(base+'/receipts');await page.getByRole('button',{name:'+ Add receipt',exact:true}).click();
   const cameraInput=page.getByLabel('Take receipt photo');
   assert.equal(await cameraInput.getAttribute('type'),'file');
   assert.equal(await cameraInput.getAttribute('accept'),'image/*');
   assert.equal(await cameraInput.getAttribute('capture'),'environment');
   const existingFile=page.getByLabel('Receipt file',{exact:true});
   assert.equal(await existingFile.getAttribute('capture'),null);
   assert.equal(await existingFile.getAttribute('accept'),'application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png');
   const chooserEvent=page.waitForEvent('filechooser');
   await page.getByRole('button',{name:'Take a photo',exact:true}).click();
   const chooser=await chooserEvent;
   assert.equal(await chooser.element().getAttribute('aria-label'),'Take receipt photo');
   const control=mode.startsWith('png')?page.getByLabel('Take receipt photo'):page.getByLabel('Receipt file',{exact:true});
   await control.setInputFiles({name:receipt.originalFileName,mimeType:receipt.contentType,buffer:bytes});
   await page.locator('form.receipt-form').evaluate(form=>{form.requestSubmit();form.requestSubmit();});
   await page.getByRole('status').filter({hasText:'Reading your receipt'}).waitFor();
   assert.equal(await page.getByRole('button',{name:'Reading your receipt…',exact:true}).isDisabled(),true);
   await page.waitForFunction(()=>Object.keys(sessionStorage).some(k=>k.startsWith('life-admin-receipt-upload:')));
   release();await page.waitForURL(base+'/receipts/'+id);await page.getByRole('heading',{name:'Review receipt details',exact:true}).waitFor();
   assert.equal(uploads,1);assert.match(uploadToken,/^[a-f0-9-]{36}$/);assert.equal(extractions,0);
   await page.reload();await page.getByRole('heading',{name:'Review receipt details',exact:true}).waitFor();assert.equal(extractions,0);
   await page.waitForFunction(()=>{const img=document.querySelector('.receipt-preview img');return img?.complete && img.naturalWidth>0;});
   assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
   if(mode.includes('failure')){
    assert.equal(await page.getByLabel('Merchant',{exact:true}).inputValue(),'');
    assert.equal(await page.getByLabel('Description (optional)',{exact:true}).inputValue(),'');
    assert.equal(await page.locator('#description + .extraction-field-note').textContent(),'Could not determine automatically.');
    await page.getByRole('button',{name:'Try reading again',exact:true}).click();assert.equal(extractions,0);
    await page.getByRole('button',{name:'Confirm another attempt',exact:true}).evaluate(button=>{button.click();button.click();});
    await page.waitForFunction(()=>document.getElementById('merchant')?.value==='Oak Cafe');
    assert.equal(extractions,1);assert.equal(retryVersion,'2026-09-22T12:00:01Z');
   }
   assert.equal(await page.getByLabel('Merchant',{exact:true}).inputValue(),'Oak Cafe');
   assert.equal(await page.getByLabel('Description (optional)',{exact:true}).inputValue(),'Coffee');
   assert.equal(await page.locator('#description + .extraction-field-note').textContent(),'Suggested by Life Admin: Coffee');
   assert.equal(await page.getByLabel('Potentially tax relevant',{exact:true}).inputValue(),'no');
   await page.getByLabel('Description (optional)',{exact:true}).fill('My confirmed purchase');
   await page.getByLabel('Potentially tax relevant',{exact:true}).selectOption('yes');
   await page.getByLabel('Merchant',{exact:true}).fill('Corrected cafe');await page.getByRole('button',{name:'Save receipt',exact:true}).click();
   await page.getByRole('heading',{name:'Confirmed by you',exact:true}).waitFor();
   await page.reload();await page.getByRole('heading',{name:'Confirmed by you',exact:true}).waitFor();
   assert.equal(await page.getByLabel('Merchant',{exact:true}).inputValue(),'Corrected cafe');
   assert.equal(await page.getByLabel('Description (optional)',{exact:true}).inputValue(),'My confirmed purchase');
   assert.equal(await page.locator('#description + .extraction-field-note').textContent(),'Confirmed by you');
   assert.equal(await page.getByLabel('Potentially tax relevant',{exact:true}).inputValue(),'yes');
   assert.equal(await page.getByRole('button',{name:'Try reading again',exact:true}).count(),0);
   assert.equal(extractions,mode.includes('failure')?1:0);assert.deepEqual(unexpected,[]);assert.deepEqual(errors,[]);
  }finally{release();await context.close();await browser.close();}
 });
}
