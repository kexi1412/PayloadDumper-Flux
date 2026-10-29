'use strict';
const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const http = require('node:http');
const assert = require('node:assert/strict');
const { spawn, spawnSync } = require('node:child_process');
const puppeteer = require('puppeteer-core');
const { createFixtures, sha } = require('./helpers/fixtures.cjs');
const root = path.resolve(__dirname, '..');
const tempParent = path.join(root, '.cache', 'tests');
fs.mkdirSync(tempParent, { recursive: true });
const temp = fs.mkdtempSync(path.join(tempParent, 'flux-native-ui-'));
const screenshots = process.env.FLUX_SCREENSHOT_DIR || path.join(root, '.cache', `native-ui-screenshots-${Date.now()}`);
const fixtures = createFixtures(path.join(temp, 'fixtures'));
const profile = path.join(temp, 'profile');
const output = path.join(temp, 'outputs');
fs.mkdirSync(profile, { recursive: true });
fs.mkdirSync(output, { recursive: true });
fs.mkdirSync(screenshots, { recursive: true });
fs.writeFileSync(path.join(profile, 'preferences.json'), JSON.stringify({ theme: 'light', workers: 4, verify: true, userAgent: 'Flux-Native-UI/1', outputDir: output }));
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
  let server;
  const errors = [];
  const agents = new Set();
  try {
    const executable = process.env.FLUX_TEST_APP;
    assert(executable && fs.existsSync(executable), 'FLUX_TEST_APP must select the actual native or installed application.');
    const port = await freePort();
    const env = { ...process.env, FLUX_USER_DATA: profile, FLUX_TEST_HIDDEN: '0',
      WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: `${process.env.WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS || ''} --remote-debugging-port=${port} --remote-debugging-address=127.0.0.1 --disable-gpu`,
    };
    child = spawn(executable, [], { cwd: path.dirname(executable), env, windowsHide: true, stdio: ['ignore', 'ignore', 'inherit'] });
    await new Promise((resolve, reject) => { child.once('spawn', resolve); child.once('error', reject); });
    const deadline = Date.now() + 45000;
    while (!browser && Date.now() < deadline) {
      if (child.exitCode !== null) throw new Error(`Native app exited before WebView2 connected: ${child.exitCode}`);
      try { browser = await puppeteer.connect({ browserURL: `http://127.0.0.1:${port}`, defaultViewport: null, protocolTimeout: 30000 }); }
      catch { await delay(200); }
    }
    assert(browser, 'System WebView2 DevTools endpoint did not become ready.');
    const target = await browser.waitForTarget(target => target.type() === 'page' && /tauri[.:/]/i.test(target.url()), { timeout: 45000 });
    const page = await target.page();
    page.on('pageerror', error => errors.push(error.message));
    await page.setViewport({ width: 1440, height: 1000, deviceScaleFactor: 1 });
    await page.waitForSelector('.app-shell', { timeout: 30000 });
    await page.waitForFunction(() => document.body.innerText.includes('引擎已就绪'), { timeout: 30000 });
    const info = await page.evaluate(() => window.flux.getAppInfo());
    assert.equal(info.engineReady, true, JSON.stringify(info));
    assert.equal(info.version, require('../package.json').version);
    assert.equal(await page.evaluate(() => typeof window.require), 'undefined');
    await page.evaluate(() => document.fonts.ready);
    await noOverflow(page, '1440px light empty');
    await page.screenshot({ path: path.join(screenshots, 'native-workbench.png') });

    server = http.createServer((req, res) => {
      agents.add(req.headers['user-agent']);
      const data = fixtures.standard.buffer;
      const headers = { 'Accept-Ranges': 'bytes', 'Content-Type': 'application/octet-stream', 'Content-Disposition': 'attachment; filename="native-UI-fixture.zip"' };
      if (req.method === 'HEAD') { res.writeHead(200, { ...headers, 'Content-Length': data.length }); res.end(); return; }
      const match = /^bytes=(\d+)-(\d+)$/.exec(req.headers.range || '');
      if (!match) { res.writeHead(400); res.end(); return; }
      const start = Number(match[1]), end = Math.min(Number(match[2]), data.length - 1);
      if (start > end) { res.writeHead(416); res.end(); return; }
      res.writeHead(206, { ...headers, 'Content-Range': `bytes ${start}-${end}/${data.length}`, 'Content-Length': end - start + 1 });
      res.end(data.subarray(start, end + 1));
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    await page.click('#source-url-tab');
    await page.type('#firmware-url', `http://127.0.0.1:${server.address().port}/ota`);
    await clickText(page, '解析固件');
    await page.waitForFunction(() => document.querySelectorAll('.partition-table tbody tr').length === 4);
    await page.waitForFunction(async () => (await window.flux.getState()).phase === 'ready');
    const parsed = await page.evaluate(() => window.flux.getState());
    assert.equal(parsed.archive.androidVersion, '16');
    assert.equal(parsed.archive.securityPatchLevel, '2026-10-01');
    await noOverflow(page, '1440px parsed');
    await page.screenshot({ path: path.join(screenshots, 'native-parsed.png') });
    console.log('PASS system WebView2 / native bridge / HTTP OTA UI parse / metadata / partition table');

    await page.type('input[aria-label="搜索分区"]', 'vendor');
    assert.equal(await page.$$eval('.partition-table tbody tr', rows => rows.length), 1);
    await page.click('input[aria-label="全选当前可见分区"]');
    await page.click('button[aria-label="清除分区搜索"]');
    await clickText(page, '全部提取');
    await page.waitForFunction(async () => { const state = await window.flux.getState(); return state.phase === 'ready' && state.partitions.every(p => p.extractState === 'DONE'); }, { timeout: 30000 });
    const state = await page.evaluate(() => window.flux.getState());
    for (const [name, expected] of Object.entries(fixtures.standard.expected)) assert.equal(sha(fs.readFileSync(path.join(state.outputDir, name))).toString('hex'), sha(expected).toString('hex'));
    await page.waitForFunction(() => [...document.querySelectorAll('.partition-status')].filter(el => el.innerText.includes('SHA-256 通过')).length === 4);
    assert(agents.has('Flux-Native-UI/1'), [...agents].join(','));
    const history = await page.evaluate(() => window.flux.getHistory());
    assert.equal(history.length, 1); assert.equal(history[0].completed, 4); assert.equal(history[0].status, 'completed');
    await page.click('button[aria-label="任务记录"]');
    await page.waitForFunction(() => document.querySelector('.history-page')?.getClientRects().length > 0);
    await page.screenshot({ path: path.join(screenshots, 'native-history.png') });
    console.log('PASS real native extraction / exact output SHA-256 / custom UA / persisted history');

    await page.click('button[aria-label="固件提取"]');
    await page.click('button[aria-label="切换主题，当前浅色"]');
    await page.waitForFunction(() => document.documentElement.dataset.theme === 'dark');
    await page.screenshot({ path: path.join(screenshots, 'native-workbench-dark.png') });
    await page.setViewport({ width: 1280, height: 800, deviceScaleFactor: 1 });
    await noOverflow(page, '1280px dark');
    await page.setViewport({ width: 960, height: 700, deviceScaleFactor: 1 });
    await noOverflow(page, '960px dark');
    await page.screenshot({ path: path.join(screenshots, 'native-compact.png') });
    await page.setViewport({ width: 1440, height: 1000, deviceScaleFactor: 1 });
    await page.click('button[aria-label="设置"]');
    await page.click('#worker-count', { clickCount: 3 });
    await page.keyboard.press('Backspace'); await page.type('#worker-count', '6');
    await clickText(page, '保存设置');
    await page.waitForFunction(async () => (await window.flux.loadPreferences()).workers === 6);
    await noOverflow(page, 'settings');
    console.log('PASS preserved light/dark UI / 1440,1280,960px layouts / settings persistence');

    await page.click('button[aria-label="固件提取"]');
    await page.click('#source-file-tab');
    await page.waitForFunction(async () => (await window.flux.getState()).archive === null);
    assert.equal(await page.$$eval('.partition-footer button', buttons => buttons.some(button => !button.disabled)), false);
    assert.equal(await page.$$eval('.partition-table tbody tr', rows => rows.length), 0);
    assert.deepEqual(errors, []);
    console.log('PASS source-change invalidation / no renderer exceptions');
    console.log(`SCREENSHOTS=${screenshots}`);
    await browser.close(); browser = null;
  } finally {
    if (browser) await browser.close().catch(() => {});
    if (child && child.exitCode === null) {
      await Promise.race([new Promise(resolve => child.once('exit', resolve)), delay(3000)]);
      if (child.exitCode === null) {
        if (process.platform === 'win32') spawnSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore' });
        else child.kill();
        await Promise.race([new Promise(resolve => child.once('exit', resolve)), delay(3000)]);
      }
    }
    if (server) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
    const resolved = fs.realpathSync(temp);
    assert.equal(path.dirname(resolved).toLowerCase(), fs.realpathSync(tempParent).toLowerCase());
    assert(path.basename(resolved).startsWith('flux-native-ui-'));
    fs.rmSync(resolved, { recursive: true, maxRetries: 10, retryDelay: 200 });
  }
}
main().catch(error => { console.error(error.stack); process.exitCode = 1; });
