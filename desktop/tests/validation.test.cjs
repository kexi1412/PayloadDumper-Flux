'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const v = require('../electron/validation.cjs');
const root = path.resolve('test-output');
const opts = { workers: 4, verify: true, userAgent: '' };

test('validates local and HTTP sources without conflating them', () => {
  const local = v.parseInput({ source: { kind: 'file', value: path.join(root, '固件.zip') }, outputDir: root, options: opts });
  assert.equal(local.source.kind, 'file');
  const remote = v.parseInput({ source: { kind: 'url', value: 'https://example.com/fw.zip?q=a%20b' }, outputDir: root, options: opts });
  assert.equal(remote.source.value, 'https://example.com/fw.zip?q=a%20b');
  assert.throws(() => v.parseInput({ source: { kind: 'url', value: 'file:///etc/passwd' }, outputDir: root, options: opts }), /HTTP/);
  assert.throws(() => v.parseInput({ source: { kind: 'file', value: '../payload.bin' }, outputDir: root, options: opts }), /绝对路径/);
});
test('rejects invalid worker counts, header injection and relative output paths', () => {
  for (const workers of [0, 17, 1.5, '4']) assert.throws(() => v.options({ ...opts, workers }));
  assert.throws(() => v.options({ ...opts, userAgent: 'Agent\r\nAuthorization: bad' }));
  assert.throws(() => v.directory('../output'));
  assert.throws(() => v.directory(root + '\0'));
  assert.deepEqual(v.options(opts), opts);
});
test('extracts only IDs present in the parsed firmware', () => {
  const state = { partitions: [{ partitionName: 'boot' }, { partitionName: 'vendor_boot' }] };
  const result = v.extractInput({ partitions: ['boot', 'boot'], outputDir: root, options: opts }, state);
  assert.deepEqual(result.partitions, ['boot']);
  assert.throws(() => v.extractInput({ partitions: ['../system'], outputDir: root, options: opts }, state));
  assert.throws(() => v.extractInput({ partitions: [], outputDir: root, options: opts }, state));
});
test('directory containment rejects sibling prefix tricks', () => {
  assert(v.isWithin(path.join(root, 'firmware'), root));
  assert(v.isWithin(root, root));
  assert(!v.isWithin(root + '-other', root));
  assert(!v.isWithin(path.join(root, '..'), root));
});
test('persists only the defined settings fields', () => {
  assert.deepEqual(v.preferences({ ...opts, theme: 'dark', outputDir: root, unknown: 'ignored' }, { outputDir: root }), { ...opts, theme: 'dark', outputDir: root });
  assert.throws(() => v.preferences({ ...opts, theme: 'invented', outputDir: root }, { outputDir: root }));
});
