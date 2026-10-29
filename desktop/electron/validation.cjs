'use strict';
const path = require('node:path');

function text(value, label, max = 32768) {
  if (typeof value !== 'string' || !value.trim() || value.length > max || value.includes('\0')) {
    throw new Error(`${label}无效`);
  }
  return value.trim();
}
function directory(value) {
  const result = text(value, '输出目录');
  if (!path.isAbsolute(result)) throw new Error('输出目录必须是绝对路径');
  return path.resolve(result);
}
function options(value = {}) {
  if (!Number.isInteger(value.workers) || value.workers < 1 || value.workers > 16) {
    throw new Error('并行线程必须在 1–16 之间');
  }
  if (typeof value.verify !== 'boolean') throw new Error('校验设置无效');
  const userAgent = typeof value.userAgent === 'string' ? value.userAgent.trim() : '';
  if (userAgent.length > 512 || /[\r\n\0]/.test(userAgent)) throw new Error('User-Agent 不能包含换行或超过 512 字符');
  return { workers: value.workers, verify: value.verify, userAgent };
}
function preferences(value, defaults) {
  if (!value || typeof value !== 'object') throw new Error('设置无效');
  if (!['light', 'dark', 'system'].includes(value.theme)) throw new Error('主题设置无效');
  return { ...options(value), theme: value.theme, outputDir: directory(value.outputDir || defaults.outputDir) };
}
function parseInput(value) {
  if (!value || !value.source || !['file', 'url'].includes(value.source.kind)) throw new Error('请选择固件来源');
  const source = { kind: value.source.kind, value: text(value.source.value, '固件来源') };
  if (source.kind === 'file') {
    if (!path.isAbsolute(source.value)) throw new Error('本地文件必须是绝对路径');
    source.value = path.resolve(source.value);
  } else {
    let url;
    try { url = new URL(source.value); } catch { throw new Error('请输入有效的 HTTP(S) 下载直链'); }
    if (!['http:', 'https:'].includes(url.protocol) || !url.hostname) throw new Error('只支持 HTTP(S) 下载直链');
    source.value = url.href;
  }
  return { source, outputDir: directory(value.outputDir), options: options(value.options) };
}
function extractInput(value, state) {
  if (!value || !Array.isArray(value.partitions) || value.partitions.length === 0) throw new Error('请先选择分区');
  const known = new Set((state?.partitions || []).map(p => p.partitionName));
  const partitions = [...new Set(value.partitions)];
  if (partitions.length > 10000 || partitions.some(p => typeof p !== 'string' || !known.has(p))) {
    throw new Error('选择中包含当前固件不存在的分区');
  }
  return { partitions, outputDir: directory(value.outputDir), options: options(value.options) };
}
function isWithin(candidate, parent) {
  const relative = path.relative(path.resolve(parent), path.resolve(candidate));
  return relative === '' || (!relative.startsWith(`..${path.sep}`) && relative !== '..' && !path.isAbsolute(relative));
}
module.exports = { text, directory, options, preferences, parseInput, extractInput, isWithin };
