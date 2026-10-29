'use strict';
const { EventEmitter } = require('node:events');
const { spawn } = require('node:child_process');
const { randomUUID } = require('node:crypto');

const idleState = () => ({ phase: 'idle', detail: '', archive: null, partitions: [], outputDir: null, sourceLabel: null });

class EngineClient extends EventEmitter {
  constructor({ command, args, cwd, env, timeout = 30000 }) {
    super();
    this.command = command;
    this.args = args;
    this.cwd = cwd;
    this.env = env;
    this.timeout = timeout;
    this.child = null;
    this.pending = new Map();
    this.state = idleState();
    this.ready = false;
    this.stopping = false;
  }
  async start() {
    if (this.child) return;
    this.stopping = false;
    this.child = spawn(this.command, this.args, {
      cwd: this.cwd, env: this.env || process.env, windowsHide: true,
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    let buffer = '';
    this.child.stdout.setEncoding('utf8');
    this.child.stdout.on('data', chunk => {
      buffer += chunk;
      if (Buffer.byteLength(buffer) > 16 * 1024 * 1024) {
        this.fail(new Error('提取引擎响应超过大小限制'));
        this.child?.kill();
        return;
      }
      let index;
      while ((index = buffer.indexOf('\n')) !== -1) {
        const line = buffer.slice(0, index).trim();
        buffer = buffer.slice(index + 1);
        if (!line) continue;
        try { this.receive(JSON.parse(line)); }
        catch { this.emit('log', { time: Date.now(), level: 'warn', message: '忽略了非协议的引擎输出' }); }
      }
    });
    this.child.stderr.setEncoding('utf8');
    this.child.stderr.on('data', chunk => {
      const message = chunk.trim().slice(0, 4096);
      if (message) this.emit('log', { time: Date.now(), level: 'warn', message });
    });
    this.child.stdin.on('error', err => { if (!this.stopping) this.fail(err); });
    this.child.once('error', err => this.fail(new Error(`无法启动提取引擎：${err.message}`)));
    this.child.once('close', (code, signal) => {
      this.child = null;
      this.ready = false;
      const reason = new Error(`提取引擎已退出 (${signal || code || 0})`);
      this.rejectPending(reason);
      if (!this.stopping) this.fail(reason);
    });
    const state = await this.request('status');
    this.state = state;
    this.ready = true;
    this.emit('state', state);
  }
  receive(message) {
    if (message.event === 'state' && message.data && Array.isArray(message.data.partitions)) {
      this.state = message.data;
      this.emit('state', this.state);
    } else if (message.event === 'log' && message.data) {
      this.emit('log', message.data);
    } else if (typeof message.id === 'string') {
      const pending = this.pending.get(message.id);
      if (!pending) return;
      clearTimeout(pending.timer);
      this.pending.delete(message.id);
      if (message.ok) pending.resolve(message.result);
      else pending.reject(new Error(message.error || '提取引擎请求失败'));
    }
  }
  request(method, params = {}) {
    if (!this.child || this.child.killed || !this.child.stdin.writable) return Promise.reject(new Error('提取引擎尚未就绪'));
    const id = randomUUID();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`提取引擎响应超时：${method}`));
      }, this.timeout);
      this.pending.set(id, { resolve, reject, timer });
      this.child.stdin.write(JSON.stringify({ id, method, params }) + '\n', err => {
        if (!err) return;
        const pending = this.pending.get(id);
        if (pending) { clearTimeout(pending.timer); this.pending.delete(id); reject(err); }
      });
    });
  }
  rejectPending(error) {
    for (const item of this.pending.values()) { clearTimeout(item.timer); item.reject(error); }
    this.pending.clear();
  }
  fail(error) {
    this.ready = false;
    this.rejectPending(error);
    this.state = {
      ...this.state, phase: 'error', detail: error.message,
      partitions: this.state.partitions.map(p => p.extractState === 'RUNNING' ? { ...p, extractState: 'ERROR', message: error.message } : p),
    };
    this.emit('state', this.state);
    this.emit('log', { time: Date.now(), level: 'error', message: error.message });
  }
  async stop() {
    this.stopping = true;
    const child = this.child;
    if (!child) return;
    const exited = new Promise(resolve => child.once('close', resolve));
    const force = setTimeout(() => { if (this.child === child) child.kill(); }, 4000);
    try { await this.request('shutdown'); } catch { /* process exit rejects the pending ACK */ }
    child.stdin.end();
    await exited;
    clearTimeout(force);
    this.ready = false;
  }
}
module.exports = { EngineClient, idleState };
