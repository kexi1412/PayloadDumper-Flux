'use strict';
const { contextBridge, ipcRenderer, webUtils } = require('electron');
const invoke = (name, ...args) => ipcRenderer.invoke(`flux:${name}`, ...args);
const subscribe = (name, callback) => {
  if (typeof callback !== 'function') throw new TypeError('Expected a callback');
  const handler = (_event, value) => callback(value);
  ipcRenderer.on(`flux:${name}`, handler);
  return () => ipcRenderer.removeListener(`flux:${name}`, handler);
};
contextBridge.exposeInMainWorld('flux', {
  getAppInfo: () => invoke('get-app-info'),
  getState: () => invoke('get-state'),
  pickFile: () => invoke('pick-file'),
  pathForFile: file => webUtils.getPathForFile(file),
  pickOutputDir: () => invoke('pick-output-dir'),
  parse: input => invoke('parse', input),
  extract: input => invoke('extract', input),
  cancel: () => invoke('cancel'),
  reset: () => invoke('reset'),
  openPath: value => invoke('open-path', value),
  copyText: value => invoke('copy-text', value),
  loadPreferences: () => invoke('load-preferences'),
  savePreferences: value => invoke('save-preferences', value),
  getHistory: () => invoke('get-history'),
  clearHistory: () => invoke('clear-history'),
  onState: callback => subscribe('state', callback),
  onLog: callback => subscribe('log', callback),
  onHistory: callback => subscribe('history', callback),
});
