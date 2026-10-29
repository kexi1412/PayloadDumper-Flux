import { invoke, isTauri } from '@tauri-apps/api/core';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
import { getCurrentWebviewWindow } from '@tauri-apps/api/webviewWindow';
import type { AppInfo, DesktopBridge, EngineState, HistoryEntry, LocalFile, LogEntry, Preferences } from './types';

/** Install before React reads window.flux; a plain browser remains unconnected. */
export function installNativeBridge(): void {
  if (window.flux || window.top !== window || !isTauri()) return;

  // Bootstrap/commands wait for actual native listeners. This prevents a fast
  // parse from completing before React can observe the parsing -> ready events.
  let listenerBarrier: Promise<void> = Promise.resolve();
  function disposable(register: (active: () => boolean) => Promise<UnlistenFn>): () => void {
    let active = true;
    let unlisten: UnlistenFn | undefined;
    const ready = register(() => active).then((dispose) => {
      if (active) unlisten = dispose;
      else dispose(); // StrictMode/unmount happened while listen() was pending.
    });
    listenerBarrier = Promise.all([listenerBarrier, ready]).then(() => undefined);
    // Keep a rejection on the barrier so bootstrap reports a genuine failed
    // event connection, but attach a handler immediately to avoid orphan errors.
    void listenerBarrier.catch((error: unknown) => console.error('Flux native event connection failed', error));
    return () => {
      if (!active) return;
      active = false;
      unlisten?.();
      unlisten = undefined;
    };
  }

  function subscribe<T>(name: string, callback: (value: T) => void): () => void {
    if (typeof callback !== 'function') throw new TypeError('Expected a callback');
    return disposable((active) => listen<T>(`flux:${name}`, (event) => {
      if (active()) callback(event.payload);
    }));
  }

  async function request<T>(method: string, params: unknown = null): Promise<T> {
    await listenerBarrier;
    return invoke<T>('flux_invoke', { method, params });
  }

  const bridge: DesktopBridge = {
    getAppInfo: () => request<AppInfo>('get-app-info'),
    getState: () => request<EngineState>('get-state'),
    pickFile: () => request<LocalFile | null>('pick-file'),
    // WebView2 DOM File objects do not expose an absolute path. Only native
    // drag-drop paths are accepted; never derive a full path from File.name.
    pathForFile: () => '',
    pickOutputDir: () => request<string | null>('pick-output-dir'),
    parse: (input) => request<void>('parse', input),
    extract: (input) => request<void>('extract', input),
    cancel: () => request<void>('cancel'),
    reset: () => request<void>('reset'),
    openPath: (path) => request<void>('open-path', path),
    copyText: (text) => request<void>('copy-text', text),
    loadPreferences: () => request<Preferences>('load-preferences'),
    savePreferences: (preferences) => request<Preferences>('save-preferences', preferences),
    getHistory: () => request<HistoryEntry[]>('get-history'),
    clearHistory: () => request<void>('clear-history'),
    onState: (callback) => subscribe<EngineState>('state', callback),
    onLog: (callback) => subscribe<LogEntry>('log', callback),
    onHistory: (callback) => subscribe<HistoryEntry[]>('history', callback),
    onFileDrop: (callback, onError) => {
      if (typeof callback !== 'function') throw new TypeError('Expected a callback');
      return disposable((active) => getCurrentWebviewWindow().onDragDropEvent((event) => {
        if (!active() || event.payload.type !== 'drop') return;
        // Rust consumes the corresponding real native Drop event, validates a
        // regular nonempty file with stat, and independently enforces busy.
        void request<LocalFile>('file-from-drop', { paths: event.payload.paths }).then((file) => {
          if (active()) callback(file);
        }).catch((error: unknown) => {
          if (!active()) return;
          if (onError) onError(error);
          else console.error('Flux native file drop failed', error);
        });
      }));
    },
  };
  Object.defineProperty(window, 'flux', { value: Object.freeze(bridge), enumerable: true, writable: false, configurable: false });
}
