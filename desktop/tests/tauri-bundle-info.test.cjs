'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { expectedNsisImage } = require('../scripts/tauri-bundle-info.cjs');
const marker = '__TAURI_BUNDLE_TYPE_VAR_UNK';
test('NSIS expected image changes only the three official marker bytes', () => {
  const raw = Buffer.from('MZ\0unaltered-prefix' + marker + '\0unaltered-tail');
  const copy = Buffer.from(raw);
  const result = expectedNsisImage(raw);
  assert.deepEqual(raw, copy); assert.equal(result.data.length, raw.length);
  assert.deepEqual(result.changedBytes, [result.offset + 24, result.offset + 25, result.offset + 26]);
  assert.equal(result.data.subarray(result.offset, result.offset + 27).toString(), '__TAURI_BUNDLE_TYPE_VAR_NSS');
  assert.equal(result.occurrenceCount, 1);
});
test('Pinned bundler uses the first marker, not a global replacement', () => {
  const raw = Buffer.from(marker + 'between' + marker);
  const result = expectedNsisImage(raw);
  assert.equal(result.occurrenceCount, 2);
  assert.equal(result.data.subarray(27).toString(), raw.subarray(27).toString());
});
test('Absent or already patched marker never silently passes verification', () => {
  assert.throws(() => expectedNsisImage(Buffer.from('not a Tauri image')), /Missing official/);
  assert.throws(() => expectedNsisImage(Buffer.from('__TAURI_BUNDLE_TYPE_VAR_NSS')), /Missing official/);
});
