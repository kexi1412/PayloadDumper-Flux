'use strict';
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const net = require('node:net');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const puppeteer = require('puppeteer-core');
const { createFixtures, sha } = require('./helpers/fixtures.cjs');
const root = path.resolve(__dirname, '..');
const tempParent = path.join(root, '.cache', 'tests');
fs.mkdirSync(tempParent, { recursive: true });
const temp = fs.mkdtempSync(path.join(tempParent, 'flux-pc-ui-'));
const screenshots = process.env.FLUX_SCREENSHOT_DIR || path.join(root, '.cache', `ui-screenshots-${Date.now()}`);
const fixtures = createFixtures(path.join(temp, 'fixtures'));
const profile = path.join(temp, 'profile');
const output = path.join(temp, 'outputs');
fs.mkdirSync(profile, { recursive: true });
fs.mkdirSync(screenshots, { recursive: true });
fs.writeFileSync(path.join(profile, 'preferences.json'), JSON.stringify({ theme: 'light', workers: 4, verify: true, userAgent: '', outputDir: output }));
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function freePort() { const server = net.createServer(); await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); const port = server.address().port; await new Promise(resolve => server.close(resolve)); return port; }
async function clickText(page, label) {
  await page.evaluate(label => {
    const button = [...document.querySelectorAll('button')].find(el => el.getClientRects().length && el.innerText.trim().startsWith(label));
    if (!button || button.disabled) throw new Error(`Button unavailable: ${label}`);
    button.click();
  }, label);
}
async function noOverflow(page, label) {
  const result = await page.evaluate(() => ({ width: innerWidth, document: document.documentElement.scrollWidth,
    overflow: [...document.querySelectorAll('.card')].filter(el => el.getClientRects().length && el.getBoundingClientRect().right > innerWidth + 1).map(el => el.className) }));
  assert(result.document <= result.width + 1 && result.overflow.length === 0, `${label}: ${JSON.stringify(result)}`);
}
async function main() {
  let child;
  let browser;
  const errors = [];
  try {
    const port = await freePort();
    const executable = process.env.FLUX_TEST_APP || require('electron');
    const args = [`--remote-debugging-port=${port}`, '--remote-debugging-address=127.0.0.1', '--enable-logging=stderr', '--disable-gpu'];
    if (!process.env.FLUX_TEST_APP) args.push(root);
    const env = { ...process.env, FLUX_USER_DATA: profile, FLUX_TEST_HIDDEN: '1' };
    delete env.ELECTRON_RUN_AS_NODE;
    child = spawn(executable, args, { cwd: root, env, windowsHide: true, stdio: ['ignore', 'ignore', 'inherit'] });
    const spawned = new Promise((resolve, reject) => { child.once('spawn', resolve); child.once('error', reject); });
    await spawned;
    const deadline = Date.now() + 45000;
    while (!browser && Date.now() < deadline) {
      if (child.exitCode !== null) throw new Error(`Electron exited before connecting: ${child.exitCode}`);
      try { browser = await puppeteer.connect({ browserURL: `http://127.0.0.1:${port}`, defaultViewport: null }); }
      catch { await delay(200); }
    }
    assert(browser, 'Electron DevTools endpoint did not become ready');
    const target = await browser.waitForTarget(target => target.type() === 'page' && target.url().startsWith('file:'), { timeout: 45000 });
    const page = await target.page();
    page.on('pageerror', error => errors.push(error.message));
    await page.setViewport({ width: 1440, height: 1000, deviceScaleFactor: 1 });
    await page.waitForSelector('.app-shell', { timeout: 30000 });
    await page.waitForFunction(() => document.body.innerText.includes('引擎已就绪'), { timeout: 30000 });
    const info = await page.evaluate(() => window.flux.getAppInfo());
    assert.equal(info.engineReady, true, JSON.stringify(info));
    assert.equal(await page.evaluate(() => typeof window.require), 'undefined');
    await page.evaluate(() => document.fonts.ready);
    await noOverflow(page, '1440px light empty');
    await page.screenshot({ path: path.join(screenshots, 'pc-workbench.png') });

    // Chromium's drag-data API supplies an actual local File, exercising preload
    // webUtils.getPathForFile and the real native file path rather than a mock bridge.
    const zone = await page.$('.drop-zone');
    const box = await zone.boundingBox();
    const cdp = await page.createCDPSession();
    const data = { items: [], files: [fixtures.standard.path], dragOperationsMask: 1 };
    for (const type of ['dragEnter', 'dragOver', 'drop']) await cdp.send('Input.dispatchDragEvent', { type, x: box.x + box.width / 2, y: box.y + box.height / 2, data });
    await page.waitForFunction(() => document.querySelector('.drop-zone__filename')?.textContent.includes('standard-fixture.zip'));
    await clickText(page, '解析固件');
    await page.waitForFunction(() => document.querySelectorAll('.partition-table tbody tr').length === 4);
    await page.waitForFunction(async () => (await window.flux.getState()).phase === 'ready');
    await noOverflow(page, '1440px parsed');
    await page.screenshot({ path: path.join(screenshots, 'pc-parsed.png') });
    console.log('PASS Electron sandbox / real file drag-drop / parse / metadata / partition table');

    await page.type('input[aria-label="搜索分区"]', 'vendor');
    assert.equal(await page.$$eval('.partition-table tbody tr', rows => rows.length), 1);
    await page.click('input[aria-label="全选当前可见分区"]');
    await page.click('button[aria-label="清除分区搜索"]');
    await clickText(page, '全部提取');
    await page.waitForFunction(async () => { const state = await window.flux.getState(); return state.phase === 'ready' && state.partitions.every(p => p.extractState === 'DONE'); }, { timeout: 30000 });
    const state = await page.evaluate(() => window.flux.getState());
    for (const [name, expected] of Object.entries(fixtures.standard.expected)) assert.equal(sha(fs.readFileSync(path.join(state.outputDir, name))).toString('hex'), sha(expected).toString('hex'));
    await page.waitForFunction(() => [...document.querySelectorAll('.partition-status')].filter(el => el.innerText.includes('SHA-256 通过')).length === 4);
    const history = await page.evaluate(() => window.flux.getHistory());
    assert.equal(history.length, 1); assert.equal(history[0].completed, 4); assert.equal(history[0].status, 'completed');
    await page.click('button[aria-label="任务记录"]');
    await page.waitForFunction(() => document.querySelector('.history-page')?.getClientRects().length > 0);
    await page.screenshot({ path: path.join(screenshots, 'pc-history.png') });
    console.log('PASS search / visible selection / extraction / output SHA-256 / persisted history');

    await page.click('button[aria-label="固件提取"]');
    await page.click('button[aria-label="切换主题，当前浅色"]');
    await page.waitForFunction(() => document.documentElement.dataset.theme === 'dark');
    await page.screenshot({ path: path.join(screenshots, 'pc-workbench-dark.png') });
    await page.setViewport({ width: 1280, height: 800, deviceScaleFactor: 1 });
    await noOverflow(page, '1280px dark');
    await page.setViewport({ width: 960, height: 700, deviceScaleFactor: 1 });
    await noOverflow(page, '960px dark');
    await page.screenshot({ path: path.join(screenshots, 'pc-compact.png') });
    await page.setViewport({ width: 1440, height: 1000, deviceScaleFactor: 1 });
    await page.click('button[aria-label="设置"]');
    await page.click('#worker-count', { clickCount: 3 });
    await page.keyboard.press('Backspace'); await page.type('#worker-count', '6');
    await clickText(page, '保存设置');
    await page.waitForFunction(async () => (await window.flux.loadPreferences()).workers === 6);
    await noOverflow(page, 'settings');
    console.log('PASS light/dark / 1440,1280,960px layout / settings persistence');

    // Changing the visible source must invalidate the old parsed result, even
    // before another parse is submitted. The old firmware must not be extractable.
    await page.click('button[aria-label="固件提取"]');
    await page.click('#source-url-tab');
    await page.waitForFunction(async () => (await window.flux.getState()).archive === null);
    const canExtractOld = await page.$$eval('.partition-footer button', buttons => buttons.some(button => !button.disabled));
    assert.equal(canExtractOld, false);
    assert.equal(await page.$$eval('.partition-table tbody tr', rows => rows.length), 0);
    assert.deepEqual(errors, []);
    console.log('PASS source-change invalidation / no renderer exceptions');
    console.log(`SCREENSHOTS=${screenshots}`);
    await browser.close(); browser = null;
  } finally {
    if (browser) await browser.close().catch(() => {});
    if (child && child.exitCode === null) {
      await Promise.race([new Promise(resolve => child.once('exit', resolve)), delay(5000)]);
      if (child.exitCode === null) child.kill();
    }
    const resolved = fs.realpathSync(temp);
    assert.equal(path.dirname(resolved).toLowerCase(), fs.realpathSync(tempParent).toLowerCase());
    assert(path.basename(resolved).startsWith('flux-pc-ui-'));
    fs.rmSync(resolved, { recursive: true, maxRetries: 10, retryDelay: 200 });
  }
}
main().catch(error => { console.error(error.stack); process.exitCode = 1; });
