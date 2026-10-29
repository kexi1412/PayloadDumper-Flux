'use strict';
const { app, BrowserWindow, clipboard, dialog, ipcMain, shell, session } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { randomUUID } = require('node:crypto');
const { EngineClient, idleState } = require('./engine-client.cjs');
const validate = require('./validation.cjs');

// Separate the desktop profile from any Android/browser application. Test runs may
// select an isolated profile without touching the user's real settings/history.
if (process.env.FLUX_USER_DATA) app.setPath('userData', path.resolve(process.env.FLUX_USER_DATA));
if (!app.requestSingleInstanceLock()) app.quit();

let window;
let engine;
let preferences;
let history = [];
let currentTask = null;
let shuttingDown = false;
let allowClose = false;
const chosenRoots = new Set();
const rendererFile = path.resolve(__dirname, '../dist/index.html');
const rendererUrl = pathToFileURL(rendererFile).href;
const activePhases = new Set(['parsing', 'extracting', 'cancelling']);
const profileFile = name => path.join(app.getPath('userData'), name);
const busy = () => activePhases.has(engine?.state.phase);
const send = (name, data) => {
  if (window && !window.isDestroyed()) window.webContents.send(`flux:${name}`, data);
};
function loadJson(name, fallback) {
  try { return JSON.parse(fs.readFileSync(profileFile(name), 'utf8')); }
  catch { return fallback; }
}
function saveJson(name, value) {
  fs.mkdirSync(app.getPath('userData'), { recursive: true });
  const destination = profileFile(name);
  const temporary = `${destination}.${randomUUID()}.tmp`;
  fs.writeFileSync(temporary, JSON.stringify(value, null, 2), { mode: 0o600 });
  // Both paths are fixed profile-owned files, never supplied by a renderer.
  fs.renameSync(temporary, destination);
}
function guard(callback) {
  return async (event, ...args) => {
    if (!window || event.sender !== window.webContents || event.senderFrame !== window.webContents.mainFrame) {
      throw new Error('拒绝非主窗口请求');
    }
    if (event.senderFrame.url.split('#')[0].split('?')[0] !== rendererUrl) throw new Error('拒绝未知页面请求');
    return callback(...args);
  };
}
function handle(name, callback) { ipcMain.handle(`flux:${name}`, guard(callback)); }
function requireIdle() { if (busy()) throw new Error('请先等待任务结束或暂停当前任务'); }
function requireEngine() { if (!engine?.ready) throw new Error('提取引擎未就绪，请检查 Java 运行时和引擎文件'); }

function finishHistory(state) {
  if (!currentTask) return;
  if (state.phase === 'extracting') { currentTask.started = true; return; }
  if (!currentTask.started || !['ready', 'error'].includes(state.phase)) return;
  const selected = new Set(currentTask.partitions);
  const rows = state.partitions.filter(p => selected.has(p.partitionName));
  const complete = rows.filter(p => p.extractState === 'DONE');
  const failed = rows.filter(p => p.extractState === 'ERROR');
  const record = {
    id: randomUUID(), archiveName: state.archive?.fileName || currentTask.archiveName,
    outputDir: state.outputDir || currentTask.outputDir,
    startedAt: currentTask.startedAt, finishedAt: Date.now(),
    totalBytes: complete.reduce((sum, p) => sum + p.size, 0), completed: complete.length,
    failed: failed.length,
    status: failed.length || state.phase === 'error' ? 'failed' : complete.length === selected.size ? 'completed' : 'cancelled',
    partitions: [...selected],
  };
  history = [record, ...history].slice(0, 100);
  currentTask = null;
  try { saveJson('history.json', history); }
  catch (error) { send('log', { time: Date.now(), level: 'warn', message: `记录保存失败：${error.message}` }); }
  send('history', history);
}

function registerIpc(defaults) {
  handle('get-app-info', () => ({ version: app.getVersion(), platform: process.platform, defaultOutputDir: defaults.outputDir, engineReady: !!engine?.ready }));
  handle('get-state', () => engine?.state || idleState());
  handle('pick-file', async () => {
    requireIdle();
    const result = await dialog.showOpenDialog(window, {
      title: '选择固件文件', properties: ['openFile'],
      filters: [{ name: 'Android 固件', extensions: ['bin', 'zip', 'app', 'img'] }, { name: '所有文件', extensions: ['*'] }],
    });
    if (result.canceled || !result.filePaths[0]) return null;
    const file = result.filePaths[0];
    const stat = fs.statSync(file);
    if (!stat.isFile()) throw new Error('所选项目不是文件');
    return { path: file, name: path.basename(file), size: stat.size };
  });
  handle('pick-output-dir', async () => {
    requireIdle();
    const result = await dialog.showOpenDialog(window, {
      title: '选择镜像输出目录', defaultPath: preferences.outputDir,
      properties: ['openDirectory', 'createDirectory'],
    });
    const selected = !result.canceled && result.filePaths[0];
    if (selected) chosenRoots.add(path.resolve(selected));
    return selected || null;
  });
  handle('parse', async input => {
    requireIdle(); requireEngine();
    const params = validate.parseInput(input);
    if (params.source.kind === 'file') {
      const stat = await fs.promises.stat(params.source.value);
      if (!stat.isFile() || stat.size === 0) throw new Error('固件文件为空或不是普通文件');
    }
    chosenRoots.add(params.outputDir);
    await engine.request('parse', params);
  });
  handle('extract', async input => {
    requireIdle(); requireEngine();
    const params = validate.extractInput(input, engine.state);
    chosenRoots.add(params.outputDir);
    currentTask = {
      startedAt: Date.now(), started: false, partitions: params.partitions,
      archiveName: engine.state.archive?.fileName || '', outputDir: params.outputDir,
    };
    try { await engine.request('extract', params); }
    catch (error) { currentTask = null; throw error; }
  });
  handle('cancel', async () => { requireEngine(); await engine.request('cancel'); });
  handle('reset', async () => { requireIdle(); requireEngine(); await engine.request('reset'); });
  handle('open-path', async value => {
    const directory = validate.directory(value);
    const roots = [preferences.outputDir, ...chosenRoots, ...history.map(h => h.outputDir)];
    if (!roots.some(root => validate.isWithin(directory, root))) throw new Error('只能打开当前或历史任务的输出目录');
    const stat = await fs.promises.stat(directory);
    if (!stat.isDirectory()) throw new Error('目标不是输出文件夹');
    const error = await shell.openPath(directory);
    if (error) throw new Error(error);
  });
  handle('copy-text', value => {
    const text = validate.text(value, '复制内容', 2 * 1024 * 1024);
    clipboard.writeText(text);
  });
  handle('load-preferences', () => preferences);
  handle('save-preferences', input => {
    const next = validate.preferences(input, defaults);
    if (busy() && (next.workers !== preferences.workers || next.verify !== preferences.verify || next.userAgent !== preferences.userAgent || next.outputDir !== preferences.outputDir)) {
      throw new Error('任务运行时不能更改提取设置');
    }
    saveJson('preferences.json', next);
    preferences = next;
    chosenRoots.add(next.outputDir);
    return next;
  });
  handle('get-history', () => history);
  handle('clear-history', () => {
    history = [];
    saveJson('history.json', history);
    send('history', history);
  });
}

async function startEngine() {
  const executable = process.platform === 'win32' ? 'java.exe' : 'java';
  const base = app.isPackaged ? process.resourcesPath : path.resolve(__dirname, '..');
  const jar = app.isPackaged ? path.join(base, 'engine/flux-engine.jar') : path.join(base, 'engine/build/libs/flux-engine.jar');
  const bundled = path.join(base, 'runtime', 'bin', executable);
  const java = process.env.FLUX_JAVA || (fs.existsSync(bundled) ? bundled : process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', executable) : executable);
  engine = new EngineClient({
    command: java,
    args: ['-Xms64m', '-Xmx2048m', `-Dflux.cacheDir=${path.join(app.getPath('userData'), 'engine-cache')}`, '-jar', jar],
    cwd: base,
  });
  engine.on('state', state => { finishHistory(state); send('state', state); });
  engine.on('log', entry => send('log', entry));
  if (!fs.existsSync(jar)) {
    engine.fail(new Error('缺少提取引擎。源码运行请先执行 pnpm engine:build；发行包请完整解压。'));
    return;
  }
  try { await engine.start(); }
  catch (error) { engine.fail(error); }
}

function createWindow() {
  window = new BrowserWindow({
    width: 1440, height: 1000, minWidth: 960, minHeight: 700,
    title: 'Payload Dumper Flux · PC', backgroundColor: '#f7f8fa',
    icon: path.resolve(__dirname, '../assets/icon.png'),
    autoHideMenuBar: true, show: process.env.FLUX_TEST_HIDDEN !== '1',
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true, nodeIntegration: false, sandbox: true,
      webSecurity: true, allowRunningInsecureContent: false,
    },
  });
  window.webContents.setWindowOpenHandler(({ url }) => {
    try { const link = new URL(url); if (link.protocol === 'https:' && link.hostname === 'github.com') void shell.openExternal(url); } catch { /* reject malformed URLs */ }
    return { action: 'deny' };
  });
  window.webContents.on('will-navigate', (event, url) => { if (url !== rendererUrl) event.preventDefault(); });
  window.webContents.on('will-attach-webview', event => event.preventDefault());
  window.on('close', event => {
    if (allowClose || shuttingDown || !busy()) return;
    event.preventDefault();
    void dialog.showMessageBox(window, {
      type: 'question', title: '任务仍在进行', message: '退出并暂停当前任务？',
      detail: '已完成的 payload 操作会保留，重新打开同一固件后可继续。',
      buttons: ['继续提取', '退出应用'], defaultId: 0, cancelId: 0,
    }).then(({ response }) => { if (response === 1) { allowClose = true; app.quit(); } });
  });
  void window.loadFile(rendererFile);
}

app.on('second-instance', () => { if (window) { if (window.isMinimized()) window.restore(); window.show(); window.focus(); } });
app.on('window-all-closed', () => app.quit());
app.on('before-quit', event => {
  if (shuttingDown) return;
  event.preventDefault();
  shuttingDown = true;
  void (async () => {
    if (engine?.ready && busy()) { try { await engine.request('cancel'); } catch { /* shutdown still kills the worker */ } }
    await engine?.stop();
    app.quit();
  })();
});
app.whenReady().then(async () => {
  session.defaultSession.setPermissionRequestHandler((_contents, _permission, callback) => callback(false));
  const defaults = { workers: 4, verify: true, userAgent: '', theme: 'light', outputDir: path.join(app.getPath('downloads'), 'PayloadDumper-Flux') };
  try { preferences = validate.preferences(loadJson('preferences.json', defaults), defaults); }
  catch { preferences = defaults; }
  const loaded = loadJson('history.json', []);
  history = Array.isArray(loaded) ? loaded.filter(h => h && typeof h.outputDir === 'string' && typeof h.id === 'string' && Array.isArray(h.partitions)).slice(0, 100) : [];
  chosenRoots.add(preferences.outputDir);
  fs.mkdirSync(preferences.outputDir, { recursive: true });
  registerIpc(defaults);
  await startEngine();
  createWindow();
}).catch(error => { dialog.showErrorBox('无法启动 Payload Dumper Flux', error.message); app.quit(); });
