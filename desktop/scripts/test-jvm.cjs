'use strict';
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const result = spawnSync(process.execPath, [path.resolve(__dirname, '../tests/engine-smoke.cjs')], {
  stdio: 'inherit', env: { ...process.env, FLUX_JVM_TEST: '1' }, cwd: path.resolve(__dirname, '..'),
});
process.exitCode = result.status === null ? 1 : result.status;
