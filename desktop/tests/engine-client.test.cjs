'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { EngineClient } = require('../electron/engine-client.cjs');
const create = () => new EngineClient({ command: process.execPath, args: [path.join(__dirname, 'helpers/mock-engine.cjs')], timeout: 1000 });

test('starts the engine, handles fragmented UTF-8 and shuts down', async () => {
  const client = create();
  try {
    await client.start();
    assert.equal(client.ready, true);
    assert.equal(client.state.phase, 'idle');
    const log = new Promise(resolve => client.once('log', resolve));
    await client.request('unicode');
    assert.equal((await log).message, '固件读取完成');
  } finally { await client.stop(); }
  assert.equal(client.ready, false);
});
test('request errors do not corrupt the next response', async () => {
  const client = create();
  try {
    await client.start();
    await assert.rejects(client.request('fail'), /fixture rejected/);
    assert.equal((await client.request('status')).phase, 'idle');
  } finally { await client.stop(); }
});
test('failed process creation reports an error and can be closed', async () => {
  const client = new EngineClient({ command: path.join(__dirname, 'definitely-missing-java.exe'), args: [], timeout: 500 });
  await assert.rejects(client.start(), /启动提取引擎|ENOENT/);
  await new Promise(resolve => setImmediate(resolve));
  await client.stop();
  assert.equal(client.ready, false);
});

test('times out a missing response and clears pending requests', async () => {
  const client = create();
  try {
    await client.start();
    client.timeout = 50;
    await assert.rejects(client.request('silent'), /超时/);
    assert.equal(client.pending.size, 0);
  } finally { await client.stop(); }
});
