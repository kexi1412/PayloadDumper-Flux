'use strict';
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const http = require('node:http');
const { EngineClient } = require('../electron/engine-client.cjs');
const { createFixtures, sha } = require('./helpers/fixtures.cjs');
const root = path.resolve(__dirname, '..');
const tempParent = path.join(root, '.cache', 'tests');
fs.mkdirSync(tempParent, { recursive: true });
const temp = fs.mkdtempSync(path.join(tempParent, 'flux-pc-smoke-'));
const fixtures = createFixtures(path.join(temp, 'fixtures'));
const output = path.join(temp, 'outputs');
const javaName = process.platform === 'win32' ? 'java.exe' : 'java';
const bundled = path.join(root, 'runtime/bin', javaName);
const java = process.env.FLUX_JAVA || (fs.existsSync(bundled) ? bundled : process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', javaName) : javaName);
const jar = process.env.FLUX_ENGINE_JAR || path.join(root, 'engine/build/libs/flux-engine.jar');
const legacy = process.env.FLUX_JVM_TEST === '1';
const native = process.env.FLUX_ENGINE_BIN || path.join(root, 'native-engine/target/release', process.platform === 'win32' ? 'flux-native-engine.exe' : 'flux-native-engine');
const packaged = process.env.FLUX_ENGINE_PACKAGED === '1';
const client = new EngineClient({
  command: legacy ? java : native,
  args: legacy ? [`-Dflux.cacheDir=${path.join(temp, 'cache')}`, '-jar', jar] : packaged ? ['--engine'] : [],
  env: { ...process.env, FLUX_CACHE_DIR: path.join(temp, 'cache') }, cwd: root,
});
const options = { workers: 4, verify: true, userAgent: 'Flux-PC-Smoke/2' };
const logs = [];
client.on('log', entry => logs.push(entry));
function nextState(predicate, timeout = 30000) {
  let handler;
  let timer;
  const promise = new Promise((resolve, reject) => {
    handler = state => { if (predicate(state)) { clearTimeout(timer); client.off('state', handler); resolve(state); } };
    timer = setTimeout(() => { client.off('state', handler); reject(new Error(`State wait timed out: ${JSON.stringify(client.state)}\n${JSON.stringify(logs.slice(-6))}`)); }, timeout);
    client.on('state', handler);
  });
  return promise;
}
async function parse(source) {
  const done = nextState(s => s.phase === 'ready' || s.phase === 'error');
  await client.request('parse', { source, outputDir: output, options });
  const state = await done;
  assert.equal(state.phase, 'ready', state.detail);
  return state;
}
async function extract() {
  const done = nextState(s => s.phase === 'ready' || s.phase === 'error');
  await client.request('extract', { partitions: client.state.partitions.map(p => p.partitionName), outputDir: output, options });
  return done;
}
function verifyFiles(directory, expected) {
  for (const [name, value] of Object.entries(expected)) {
    const actual = fs.readFileSync(path.join(directory, name));
    assert.equal(actual.length, value.length, name);
    assert.equal(sha(actual).toString('hex'), sha(value).toString('hex'), name);
  }
}
async function main() {
  let server;
  try {
    assert(fs.existsSync(legacy ? jar : native), legacy ? 'Build the reference JVM engine.' : 'Build the native engine or select FLUX_ENGINE_BIN.');
    await client.start();
    const parsed = await parse({ kind: 'file', value: fixtures.standard.path });
    assert.equal(parsed.partitions.length, 4);
    assert.equal(parsed.archive.androidVersion, '16');
    assert.equal(parsed.archive.securityPatchLevel, '2026-10-01');
    let finished = await extract();
    assert(finished.partitions.every(p => p.extractState === 'DONE' && p.verifyState === 'PASSED'), JSON.stringify(finished));
    verifyFiles(finished.outputDir, fixtures.standard.expected);
    console.log('PASS local OTA / metadata / four partitions / SHA-256');

    await parse({ kind: 'file', value: fixtures.selfContained.path });
    finished = await extract();
    assert(finished.partitions.every(p => p.extractState === 'DONE' && p.verifyState === 'PASSED'), JSON.stringify(finished));
    verifyFiles(finished.outputDir, fixtures.selfContained.expected);
    console.log('PASS minor 9 / self-contained REPLACE / unused src_length / SHA-256');

    await parse({ kind: 'file', value: fixtures.raw.path });
    finished = await extract();
    assert(finished.partitions.every(p => p.extractState === 'DONE'), JSON.stringify(finished));
    verifyFiles(finished.outputDir, fixtures.raw.expected);
    console.log('PASS plain ZIP / STORED / DEFLATE / exact output');

    await parse({ kind: 'file', value: fixtures.nested.path });
    finished = await extract();
    assert(finished.partitions.every(p => p.extractState === 'DONE'), JSON.stringify(finished));
    verifyFiles(path.join(finished.outputDir, 'update_sd_base'), fixtures.nested.expected);
    console.log('PASS nested ZIP / isolated inner archive / exact output');

    await parse({ kind: 'file', value: fixtures.bad.path });
    finished = await extract();
    assert(finished.partitions.every(p => p.extractState === 'ERROR'));
    console.log('PASS corrupt operation SHA-256 rejected');
    await parse({ kind: 'file', value: fixtures.delta.path });
    finished = await extract();
    assert(finished.partitions.every(p => p.extractState === 'ERROR'));
    assert(!fs.existsSync(path.join(finished.outputDir, 'boot.img')));
    console.log('PASS genuine SOURCE_COPY / minor zero / rejected before image creation');

    const agents = new Set();
    let network = fixtures.large.buffer;
    let deniedReads = 0;
    server = http.createServer((req, res) => {
      agents.add(req.headers['user-agent']);
      if (req.url === '/gone') { deniedReads++; res.writeHead(403); res.end(); return; }
      const data = req.url === '/extensionless' ? fixtures.direct.buffer : network;
      const common = { 'Accept-Ranges': 'bytes', 'Content-Type': 'application/octet-stream', 'Content-Disposition': 'attachment; filename="network-fixture"' };
      if (req.url === '/wrong-range') { res.writeHead(200, { ...common, 'Content-Length': 4 }); res.end(data.subarray(0, 4)); return; }
      if (req.method === 'HEAD') { res.writeHead(200, { ...common, 'Content-Length': data.length }); res.end(); return; }
      const range = /^bytes=(\d+)-(\d+)$/.exec(req.headers.range || '');
      if (!range) { res.writeHead(400); res.end(); return; }
      const first = Number(range[1]); const last = Math.min(Number(range[2]), data.length - 1);
      if (first > last || first < 0) { res.writeHead(416); res.end(); return; }
      setTimeout(() => {
        if (res.destroyed) return;
        res.writeHead(206, { ...common, 'Content-Range': `bytes ${first}-${last}/${data.length}`, 'Content-Length': last - first + 1 });
        res.end(data.subarray(first, last + 1));
      }, last - first >= 4095 ? 35 : 0);
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const origin = `http://127.0.0.1:${server.address().port}`;
    const url = origin + '/firmware.zip';
    await parse({ kind: 'url', value: url });
    const progress = nextState(s => s.phase === 'extracting' && s.partitions.some(p => p.extractState === 'RUNNING' && p.progress > 0 && p.progress < 1));
    await client.request('extract', { partitions: ['system'], outputDir: output, options });
    await progress;
    const paused = nextState(s => s.phase === 'ready');
    await client.request('cancel');
    const pauseState = await paused;
    assert(pauseState.partitions.some(p => p.extractState === 'PAUSED'), JSON.stringify(pauseState));
    const partialPath = path.join(pauseState.outputDir, 'system.img');
    const partialSha = sha(fs.readFileSync(partialPath)).toString('hex');
    network = fixtures.largeChanged.buffer;
    finished = await extract();
    assert.equal(finished.phase, 'error', JSON.stringify(finished));
    assert.equal(sha(fs.readFileSync(partialPath)).toString('hex'), partialSha, 'Changed manifest must not mutate the paused image');
    console.log('PASS paused source replacement / full-manifest identity mismatch / old bytes untouched');
    network = fixtures.large.buffer;
    await parse({ kind: 'url', value: url });
    finished = await extract();
    assert(finished.partitions.every(p => p.extractState === 'DONE' && p.verifyState === 'PASSED'), JSON.stringify(finished));
    verifyFiles(finished.outputDir, fixtures.large.expected);
    assert(agents.has(options.userAgent));
    console.log('PASS HTTP Range / custom UA / cancellation / identity-bound resume / SHA-256');

    await parse({ kind: 'url', value: origin + '/extensionless' });
    finished = await extract();
    assert(finished.partitions.every(p => p.extractState === 'DONE' && p.verifyState === 'PASSED'), JSON.stringify(finished));
    verifyFiles(finished.outputDir, fixtures.direct.expected);
    console.log('PASS extensionless raw payload / magic detection / nonzero minor / SHA-256');
    for (const route of ['/wrong-range', '/gone']) {
      const failed = nextState(s => s.phase === 'error');
      await client.request('parse', { source: { kind: 'url', value: origin + route }, outputDir: output, options });
      const state = await failed;
      assert(state.detail, JSON.stringify(state));
      if (route === '/gone') assert.match(state.detail, /403/);
    }
    assert.equal(deniedReads, 1, 'Permanent 403 must stop after its first failed request');
    console.log('PASS ignored Range rejected / HTTP 403 provenance / permanent failures not retried');
    await client.request('reset');
    assert.equal(client.state.phase, 'idle');
    console.log('PASS reset / shutdown');
  } finally {
    await client.stop();
    if (server) await new Promise(resolve => server.close(resolve));
    const resolved = fs.realpathSync(temp);
    assert.equal(path.dirname(resolved).toLowerCase(), fs.realpathSync(tempParent).toLowerCase());
    assert(path.basename(resolved).startsWith('flux-pc-smoke-'));
    fs.rmSync(resolved, { recursive: true });
  }
}
main().catch(error => { console.error(error.stack); process.exitCode = 1; });
