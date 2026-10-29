'use strict';
// Protocol-only fixture. Never loaded by the desktop application.
const readline = require('node:readline');
const idle = { phase: 'idle', detail: '', archive: null, partitions: [], outputDir: null, sourceLabel: null };
readline.createInterface({ input: process.stdin }).on('line', line => {
  const request = JSON.parse(line);
  if (request.method === 'silent') return;
  if (request.method === 'fail') { process.stdout.write(JSON.stringify({ id: request.id, ok: false, error: 'fixture rejected request' }) + '\n'); return; }
  if (request.method === 'unicode') {
    const payload = Buffer.from(JSON.stringify({ event: 'log', data: { time: 1, level: 'info', message: '固件读取完成' } }) + '\n');
    const cut = payload.indexOf(Buffer.from('固')) + 1;
    process.stdout.write(payload.subarray(0, cut));
    process.stdout.write(payload.subarray(cut));
  }
  process.stdout.write(JSON.stringify({ id: request.id, ok: true, result: request.method === 'status' ? idle : null }) + '\n');
  if (request.method === 'shutdown') process.exit(0);
});
