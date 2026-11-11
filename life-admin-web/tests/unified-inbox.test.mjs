import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile, mkdir } from 'node:fs/promises';
import { chromium } from 'playwright-core';
const base=process.env.LIFE_ADMIN_TEST_URL||'http://localhost:3001';
const artifacts=process.env.LIFE_ADMIN_TEST_ARTIFACTS||'/tmp/life-admin-m14-browser';
let browser;
before(async()=>{assert.ok(['localhost','127.0.0.1'].includes(new URL(base).hostname));await mkdir(artifacts,{recursive:true});browser=await chromium.launch({executablePath:process.env.CHROME_PATH||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:process.env.LIFE_ADMIN_TEST_HEADED!=='1'});});
after(async()=>{await browser?.close();});
async function workspace(){
 const context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage(),email=`m14-${randomUUID()}@example.test`,password=`Fixture-${randomUUID()}`,errors=[],ai=[];
 page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(/api\.openai\.com|\/analyze(?:\?|$)/.test(r.url()))ai.push(r.url());});
 await page.goto(base+'/register');await page.getByLabel('Email',{exact:true}).fill(email);await page.getByLabel('Password',{exact:true}).fill(password);await page.getByLabel('Confirm password',{exact:true}).fill(password);await page.getByRole('button',{name:'Create account',exact:true}).click();await page.waitForURL(base+'/');await page.locator('.unified-summary').waitFor();
 assert.equal((await api(context,'/api/development/capabilities')).mockAnalysisEnabled,true,'Run against local development, never production');
 return {context,page,email,password,errors,ai};
}
async function api(context,path){const r=await context.request.get(base+path);assert.equal(r.status(),200);return r.json();}
async function mutate(context,path,method,data){const token=await api(context,'/api/auth/csrf');const r=await context.request.fetch(base+path,{method,headers:{[token.headerName]:token.token},data});assert.ok(r.ok(),`${path}: ${r.status()}`);return r.status()===204?null:r.json();}
function day(offset){const date=new Date();date.setUTCDate(date.getUTCDate()+offset);return date.toISOString().slice(0,10);}
async function createReceipt(context,bytes,name){const token=await api(context,'/api/auth/csrf');const r=await context.request.post(base+'/api/receipts',{headers:{[token.headerName]:token.token},multipart:{file:{name,mimeType:'application/pdf',buffer:bytes}}});assert.equal(r.status(),201);return r.json();}
async function go(page,name,path){await page.getByRole('navigation').getByRole('link',{name,exact:true}).click();await page.waitForURL(base+path);}

test('unified dashboard, Inbox, upload/review/task/reminder navigation, persistence and mobile', {timeout:120000},async()=>{
 const a=await workspace();const {context,page}=a;
 try{
  assert.deepEqual(await page.locator('.unified-summary strong').allTextContents(),['0','0','0','0','0']);await page.getByText("You're all caught up.",{exact:true}).waitFor();assert.equal(await page.getByRole('button',{name:'Upload document',exact:true}).count(),1);
  await go(page,'Inbox','/inbox');await page.getByText("You're all caught up.",{exact:true}).waitFor();await go(page,'Dashboard','/');
  await page.getByRole('button',{name:'Upload document',exact:true}).click();const dialog=page.getByRole('dialog');await dialog.waitFor();await dialog.getByLabel('Choose a PDF document',{exact:true}).setInputFiles(new URL('../../docs/fixtures/TestRecord.pdf',import.meta.url).pathname);await dialog.getByRole('button',{name:'Upload document',exact:true}).click();await dialog.getByText('Document uploaded successfully',{exact:false}).waitFor();await dialog.getByRole('button',{name:'Close',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('.unified-summary strong')?.textContent==='1');await page.getByText("You're all caught up.",{exact:true}).waitFor();
  await go(page,'Documents','/documents');await page.getByRole('heading',{name:'My Documents',exact:true}).waitFor();await page.locator('.document-row').filter({hasText:'TestRecord.pdf'}).click();await page.waitForURL(/\/documents\/[a-f0-9-]{36}$/);const docId=page.url().split('/').at(-1);await page.getByRole('heading',{name:'TestRecord.pdf',exact:true}).waitFor();
  const tasks=[];for(const [title,date]of [['M14 overdue',day(-1)],['M14 due today',day(0)],['M14 due soon',day(5)],['M14 undated',null]])tasks.push(await mutate(context,`/api/documents/${docId}/tasks`,'POST',{title,dueDate:date}));
  await mutate(context,`/api/tasks/${tasks[2].id}/reminders`,'POST',{reminderDate:day(0)});
  const bytes=await readFile(new URL('../../docs/fixtures/TestRecord.pdf',import.meta.url)),receipts=[];for(let i=0;i<5;i++)receipts.push(await createReceipt(context,bytes,`M14 review ${i}.pdf`));
  await go(page,'Dashboard','/');await page.locator('.unified-summary').waitFor();assert.deepEqual(await page.locator('.unified-summary strong').allTextContents(),['1','5','4','1','5']);assert.equal(await page.locator('.attention-item').count(),8);assert.equal(await page.locator('.attention-item').first().getAttribute('data-attention-id'),`task:${tasks[0].id}`);await page.getByRole('link',{name:'View all (9) →',exact:true}).waitFor();assert.equal(await page.locator('.recent-activity li').count(),5);
  await page.screenshot({path:artifacts+'/dashboard-desktop.png',fullPage:true});
  await page.getByRole('link',{name:'View all (9) →',exact:true}).click();await page.waitForURL(base+'/inbox');await page.getByText('9 items needing attention',{exact:true}).waitFor();assert.equal(await page.locator('.attention-item').count(),9);
  const ids=await page.locator('.attention-item').evaluateAll(rows=>rows.map(r=>r.getAttribute('data-attention-id')));assert.equal(new Set(ids).size,9);
  for(const [filter,count]of [['documents',0],['receipts',5],['tasks',4],['deadlines',3],['all',9]]){await page.getByLabel('Show',{exact:true}).selectOption(filter);await page.getByText(`${count} items needing attention`,{exact:true}).waitFor();assert.equal(await page.locator('.attention-item').count(),count);}
  await page.getByLabel('Sort',{exact:true}).selectOption('newest');await page.waitForFunction(()=>document.querySelector('.attention-item')?.getAttribute('data-attention-id')?.startsWith('receipt:'));await page.getByLabel('Sort',{exact:true}).selectOption('urgent');await page.waitForFunction(id=>document.querySelector('.attention-item')?.getAttribute('data-attention-id')===`task:${id}`,tasks[0].id);
  await page.screenshot({path:artifacts+'/inbox-desktop.png',fullPage:true});
  await page.locator(`[data-attention-id="task:${tasks[0].id}"]`).click();await page.waitForURL(base+`/documents/${docId}#task-${tasks[0].id}`);await page.getByRole('checkbox',{name:'Mark M14 overdue completed',exact:true}).click();await page.getByText('Task completed.',{exact:true}).waitFor();
  await go(page,'Inbox','/inbox');await page.getByText('8 items needing attention',{exact:true}).waitFor();assert.equal(await page.locator(`[data-attention-id="task:${tasks[0].id}"]`).count(),0);
  await page.locator(`[data-attention-id="task:${tasks[2].id}"]`).click();await page.waitForURL(base+`/documents/${docId}#task-${tasks[2].id}`);await page.getByRole('heading',{name:'M14 due soon',exact:true}).waitFor();await page.getByText('Reminder',{exact:false}).first().waitFor();
  await go(page,'Inbox','/inbox');await page.getByText('8 items needing attention',{exact:true}).waitFor();await page.locator(`[data-attention-id="receipt:${receipts[0].id}"]`).click();await page.waitForURL(base+`/receipts/${receipts[0].id}`);await page.getByRole('heading',{name:'Review receipt details',exact:true}).waitFor();
  await page.getByLabel('Merchant',{exact:true}).fill('M14 reviewed merchant');await page.getByLabel('Purchase date',{exact:true}).fill(day(0));await page.getByLabel('Amount',{exact:true}).fill('12.34');await page.getByLabel('Currency',{exact:true}).selectOption('USD');await page.getByLabel('Category',{exact:true}).selectOption('OTHER');await page.getByRole('button',{name:'Save receipt',exact:true}).click();await page.getByText('Receipt saved and reviewed.',{exact:true}).waitFor();
  await go(page,'Dashboard','/');await page.locator('.unified-summary').waitFor();assert.deepEqual(await page.locator('.unified-summary strong').allTextContents(),['1','5','3','1','4']);assert.equal(await page.locator(`[data-attention-id="receipt:${receipts[0].id}"]`).count(),0);await page.locator('.recent-activity').getByText('Receipt reviewed',{exact:true}).waitFor();
  await page.getByRole('link',{name:'Add receipt',exact:true}).click();await page.waitForURL(base+'/receipts?add=1');await page.getByLabel('Receipt file',{exact:true}).waitFor();await page.getByRole('button',{name:'Cancel',exact:true}).click();await go(page,'Dashboard','/');await page.getByRole('link',{name:'View tax organization',exact:true}).click();await page.waitForURL(base+'/receipts/tax');await page.getByRole('heading',{name:'Tax organization',exact:true}).waitFor();
  await go(page,'Dashboard','/');await page.setViewportSize({width:390,height:844});await page.locator('.unified-summary').waitFor();assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await page.screenshot({path:artifacts+'/dashboard-mobile.png',fullPage:true});await go(page,'Inbox','/inbox');await page.getByText('7 items needing attention',{exact:true}).waitFor();assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await page.screenshot({path:artifacts+'/inbox-mobile.png',fullPage:true});
  await page.getByRole('button',{name:'Log out',exact:true}).click();await page.waitForURL(base+'/login');await page.getByLabel('Email',{exact:true}).fill(a.email);await page.getByLabel('Password',{exact:true}).fill(a.password);await page.getByRole('button',{name:'Log in',exact:true}).click();await page.waitForURL(base+'/');await page.locator('.unified-summary').waitFor();assert.deepEqual(await page.locator('.unified-summary strong').allTextContents(),['1','5','3','1','4']);await go(page,'Inbox','/inbox');await page.getByText('7 items needing attention',{exact:true}).waitFor();
  assert.deepEqual(a.errors,[]);assert.deepEqual(a.ai,[]);
 }finally{await context.close();}
});

test('empty account isolation and recoverable Inbox/dashboard loading errors', {timeout:90000},async()=>{
 const a=await workspace(),b=await workspace();
 try{
  await createReceipt(a.context,await readFile(new URL('../../docs/fixtures/TestRecord.pdf',import.meta.url)),'Private A receipt.pdf');await a.page.reload();await a.page.locator('.attention-item').waitFor();
  await b.page.reload();await b.page.locator('.unified-summary').waitFor();assert.deepEqual(await b.page.locator('.unified-summary strong').allTextContents(),['0','0','0','0','0']);await b.page.getByText("You're all caught up.",{exact:true}).waitFor();assert.equal(await b.page.getByText('Private A receipt.pdf',{exact:false}).count(),0);
  for(const filter of ['all','receipts','documents','tasks','deadlines'])assert.equal((await api(b.context,`/api/inbox?filter=${filter}`)).totalCount,0);assert.deepEqual((await api(b.context,'/api/dashboard')).recentActivity,[]);
  await b.page.route('**/api/inbox?**',route=>route.fulfill({status:503,contentType:'application/json',body:'{"message":"Test unavailable"}'}));await go(b.page,'Inbox','/inbox');await b.page.getByRole('alert').filter({hasText:"couldn't load your Inbox"}).waitFor();assert.equal(await b.page.getByText("You're all caught up.",{exact:true}).count(),0);await b.page.unroute('**/api/inbox?**');await b.page.getByRole('button',{name:'Try again',exact:true}).click();await b.page.getByText("You're all caught up.",{exact:true}).waitFor();
  await b.page.route('**/api/dashboard',route=>route.fulfill({status:503,contentType:'application/json',body:'{"message":"Test unavailable"}'}));await go(b.page,'Dashboard','/');await b.page.getByRole('alert').filter({hasText:"couldn't load your dashboard"}).waitFor();assert.equal(await b.page.locator('.unified-summary').count(),0);await b.page.unroute('**/api/dashboard');await b.page.getByRole('button',{name:'Try again',exact:true}).click();await b.page.locator('.unified-summary').waitFor();
  assert.deepEqual(a.errors.concat(b.errors),[]);assert.deepEqual(a.ai.concat(b.ai),[]);
 }finally{await a.context.close();await b.context.close();}
});
