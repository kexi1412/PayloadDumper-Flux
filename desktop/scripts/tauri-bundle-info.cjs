'use strict';
const assert = require('node:assert/strict');
// tauri-cli 2.12.4 pins tauri-bundler 2.10.4. Its NSIS stage patches the
// first 27-byte marker in place, then restores the raw build artifact.
// https://docs.rs/tauri-bundler/2.10.4/src/tauri_bundler/bundle.rs.html#40-218
function expectedNsisImage(raw) {
  const from = Buffer.from('__TAURI_BUNDLE_TYPE_VAR_UNK', 'ascii');
  const to = Buffer.from('__TAURI_BUNDLE_TYPE_VAR_NSS', 'ascii');
  assert.equal(from.length, 27); assert.equal(to.length, 27);
  const offset = raw.indexOf(from);
  assert(offset >= 0, 'Missing official Tauri bundle marker; do not guess the installed image.');
  const data = Buffer.from(raw); to.copy(data, offset);
  const changed = [];
  for (let i = 0; i < raw.length; i++) if (raw[i] !== data[i]) changed.push(i);
  assert.deepEqual(changed, [offset + 24, offset + 25, offset + 26]);
  return { data, offset, changedBytes: changed, occurrenceCount: raw.toString('latin1').split(from.toString('latin1')).length - 1 };
}
module.exports = { expectedNsisImage };
