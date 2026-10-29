import { useCallback, useEffect, useRef, useState } from 'react';
import type {
  AppInfo, DesktopBridge, EngineState, HistoryEntry, LocalFile, LogEntry, ParseInput, Preferences,
} from '../types';
import { errorMessage, isEngineBusy, validatePreferences, validateSourceUrl } from '../utils';

const EMPTY_STATE: EngineState = {
  phase: 'idle', detail: '', archive: null, partitions: [], outputDir: null, sourceLabel: null,
};

export type ToastTone = 'success' | 'error' | 'info';
export interface ToastMessage { id: number; message: string; tone: ToastTone }
type EngineAction = 'parse' | 'extract' | 'reset';

/** Only bridge snapshots/events update engine state. Request spinners are kept separate. */
export function useFluxDesktop() {
  const [bridge] = useState<DesktopBridge | null>(() => window.flux ?? null);
  const [appInfo, setAppInfo] = useState<AppInfo | null>(null);
  const [state, setState] = useState<EngineState>(EMPTY_STATE);
  const [preferences, setPreferences] = useState<Preferences | null>(null);
  const [history, setHistory] = useState<HistoryEntry[]>([]);
  const [historyLoaded, setHistoryLoaded] = useState(false);
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [toasts, setToasts] = useState<ToastMessage[]>([]);
  const [initialLoading, setInitialLoading] = useState(Boolean(bridge));
  const [connectionError, setConnectionError] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState<EngineAction | null>(null);
  const [cancelPending, setCancelPending] = useState(false);
  const [savingPreferences, setSavingPreferences] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(Boolean(bridge));
  const [clearingHistory, setClearingHistory] = useState(false);
  const [dialogBusy, setDialogBusy] = useState(false);
  const [sourceDirty, setSourceDirty] = useState(false);
  const [sourceResetting, setSourceResetting] = useState(false);
  const sourceEpoch = useRef(0);
  const sourceDirtyRef = useRef(false);
  const sourceInvalidated = useRef(false);
  const sourceResetPromise = useRef<Promise<void> | null>(null);
  const activeParse = useRef<{ epoch: number; acknowledged: boolean; sawParsing: boolean; ready: boolean } | null>(null);
  const mounted = useRef(false);
  const snapshotRevision = useRef(0);
  const historyRevision = useRef(0);
  const bootstrapRevision = useRef(0);
  const requestLock = useRef(false);
  const cancelLock = useRef(false);
  const preferencesLock = useRef(false);
  const dialogLock = useRef(false);
  const historyLock = useRef(false);
  const toastSequence = useRef(0);
  const toastTimers = useRef(new Map<number, ReturnType<typeof setTimeout>>());

  const appendLog = useCallback((entry: LogEntry) => {
    setLogs((entries) => [...entries.slice(-499), entry]);
  }, []);

  const dismissToast = useCallback((id: number) => {
    const timer = toastTimers.current.get(id);
    if (timer) clearTimeout(timer);
    toastTimers.current.delete(id);
    setToasts((items) => items.filter((item) => item.id !== id));
  }, []);

  const notify = useCallback((message: string, tone: ToastTone = 'info', recordError = true) => {
    if (!mounted.current) return;
    const id = ++toastSequence.current;
    setToasts((items) => [...items.filter((item) => item.message !== message).slice(-3), { id, message, tone }]);
    toastTimers.current.set(id, setTimeout(() => dismissToast(id), tone === 'error' ? 9000 : 4500));
    if (tone === 'error' && recordError) appendLog({ time: Date.now(), level: 'error', message: `[界面] ${message}` });
  }, [appendLog, dismissToast]);

  const releaseSourceGuard = useCallback(() => {
    const attempt = activeParse.current;
    if (!attempt || attempt.epoch !== sourceEpoch.current || !attempt.acknowledged || !attempt.ready) return;
    sourceDirtyRef.current = false;
    sourceInvalidated.current = false;
    activeParse.current = null;
    if (mounted.current) setSourceDirty(false);
  }, []);

  const acceptSnapshot = useCallback((snapshot: EngineState) => {
    setState(snapshot);
    const attempt = activeParse.current;
    if (!attempt || attempt.epoch !== sourceEpoch.current) return;
    if (snapshot.phase === 'parsing') attempt.sawParsing = true;
    if (snapshot.phase === 'ready' && snapshot.archive && attempt.sawParsing) attempt.ready = true;
    if (snapshot.phase === 'error' || (snapshot.phase === 'idle' && attempt.sawParsing)) {
      activeParse.current = null;
      return;
    }
    releaseSourceGuard();
  }, [releaseSourceGuard]);

  const refreshState = useCallback(async () => {
    if (!bridge) return;
    const revision = snapshotRevision.current;
    try {
      const snapshot = await bridge.getState();
      if (mounted.current && revision === snapshotRevision.current) acceptSnapshot(snapshot);
    } catch (error) {
      notify(`读取引擎状态失败：${errorMessage(error)}`, 'error');
    }
  }, [acceptSnapshot, bridge, notify]);

  const bootstrap = useCallback(async () => {
    if (!bridge) return;
    const generation = ++bootstrapRevision.current;
    const stateVersion = snapshotRevision.current;
    const historyVersion = historyRevision.current;
    setInitialLoading(true);
    setHistoryLoading(true);
    setConnectionError(null);
    const results = await Promise.allSettled([
      bridge.getAppInfo(), bridge.getState(), bridge.loadPreferences(), bridge.getHistory(),
    ] as const);
    if (!mounted.current || generation !== bootstrapRevision.current) return;
    const [infoResult, stateResult, prefsResult, historyResult] = results;
    if (infoResult.status === 'fulfilled') setAppInfo(infoResult.value);
    if (stateResult.status === 'fulfilled' && stateVersion === snapshotRevision.current) setState(stateResult.value);
    if (prefsResult.status === 'fulfilled') setPreferences(prefsResult.value);
    if (historyResult.status === 'fulfilled' && historyVersion === historyRevision.current) {
      setHistory(historyResult.value);
      setHistoryLoaded(true);
    }
    const labels = ['应用信息', '引擎状态', '偏好设置', '任务记录'];
    const errors = results.flatMap((result, index) =>
      result.status === 'rejected' ? [`${labels[index]}：${errorMessage(result.reason)}`] : []);
    if (errors.length) {
      const message = `桌面服务连接不完整。${errors.join('；')}`;
      setConnectionError(message);
      notify(message, 'error');
    }
    setInitialLoading(false);
    setHistoryLoading(false);
  }, [bridge, notify]);

  useEffect(() => {
    mounted.current = true;
    const unsubscribe: Array<() => void> = [];
    let lastEngineError = '';
    if (bridge) {
      try {
        unsubscribe.push(bridge.onState((snapshot) => {
          if (!mounted.current) return;
          snapshotRevision.current += 1;
          acceptSnapshot(snapshot);
          const failure = snapshot.phase === 'error' ? (snapshot.detail || '引擎报告错误，请检查固件来源。') : '';
          if (failure && failure !== lastEngineError) notify(failure, 'error');
          lastEngineError = failure;
        }));
        unsubscribe.push(bridge.onLog((entry) => {
          if (!mounted.current) return;
          appendLog(entry);
          if (entry.level === 'error') notify(entry.message, 'error', false);
        }));
        unsubscribe.push(bridge.onHistory((entries) => {
          if (!mounted.current) return;
          historyRevision.current += 1;
          setHistory(entries);
          setHistoryLoaded(true);
        }));
        void bootstrap();
      } catch (error) {
        const message = `无法连接桌面服务：${errorMessage(error)}`;
        setConnectionError(message);
        setInitialLoading(false);
        setHistoryLoading(false);
        notify(message, 'error');
      }
    }
    return () => {
      mounted.current = false;
      bootstrapRevision.current += 1;
      unsubscribe.forEach((dispose) => dispose());
      toastTimers.current.forEach(clearTimeout);
      toastTimers.current.clear();
    };
  }, [acceptSnapshot, appendLog, bootstrap, bridge, notify]);

  const busy = isEngineBusy(state.phase) || pendingAction !== null;
  const available = Boolean(bridge && appInfo?.engineReady && preferences && !initialLoading && !connectionError);
  const outputDir = preferences?.outputDir || appInfo?.defaultOutputDir || '';

  const runEngineCommand = useCallback(async (kind: EngineAction, operation: () => Promise<void>) => {
    if (requestLock.current) return false;
    requestLock.current = true;
    setPendingAction(kind);
    try {
      await operation();
      if (mounted.current) await refreshState();
      return true;
    } catch (error) {
      notify(errorMessage(error), 'error');
      return false;
    } finally {
      requestLock.current = false;
      if (mounted.current) setPendingAction(null);
    }
  }, [notify, refreshState]);

  const invalidateSource = useCallback(async () => {
    sourceEpoch.current += 1;
    activeParse.current = null;
    sourceDirtyRef.current = true;
    setSourceDirty(true);
    // One reset per departure from a parsed source, not one reset per URL keystroke.
    if (sourceInvalidated.current) return;
    sourceInvalidated.current = true;
    if (!bridge || !state.archive) return;
    setSourceResetting(true);
    const operation = (async () => {
      try {
        await bridge.reset();
        await refreshState();
      } catch (error) {
        notify(`来源已变更，旧结果已禁用。重置引擎失败：${errorMessage(error)}`, 'error');
      }
    })();
    sourceResetPromise.current = operation;
    await operation;
    if (sourceResetPromise.current === operation) sourceResetPromise.current = null;
    if (mounted.current) setSourceResetting(false);
  }, [bridge, notify, refreshState, state.archive]);

  const parse = useCallback(async (source: ParseInput['source']) => {
    if (!bridge || !available || busy || !preferences || preferencesLock.current || requestLock.current) return false;
    const validation = source.kind === 'url' ? validateSourceUrl(source.value) : (!source.value.trim() ? '请先选择本地固件文件。' : null);
    if (validation || !outputDir) {
      notify(validation || '请先选择输出目录。', 'error');
      return false;
    }
    const epoch = sourceEpoch.current;
    if (sourceResetPromise.current) await sourceResetPromise.current;
    if (epoch !== sourceEpoch.current || !mounted.current) return false;
    const attempt = { epoch, acknowledged: false, sawParsing: false, ready: false };
    activeParse.current = attempt;
    sourceDirtyRef.current = true;
    setSourceDirty(true);
    const success = await runEngineCommand('parse', async () => {
      await bridge.parse({
        source: { ...source, value: source.value.trim() }, outputDir,
        options: { workers: preferences.workers, verify: preferences.verify, userAgent: preferences.userAgent },
      });
      attempt.acknowledged = true;
      releaseSourceGuard();
    });
    if (!success && activeParse.current === attempt) activeParse.current = null;
    return success;
  }, [available, bridge, busy, notify, outputDir, preferences, releaseSourceGuard, runEngineCommand]);

  const extract = useCallback(async (names: string[]) => {
    if (!bridge || !available || busy || !preferences || !state.archive || sourceDirtyRef.current || sourceResetPromise.current || preferencesLock.current) return false;
    const uniqueNames = [...new Set(names)].filter((name) => state.partitions.some((partition) => partition.partitionName === name));
    if (!uniqueNames.length || !outputDir) {
      notify(!uniqueNames.length ? '请至少选择一个分区。' : '请先选择输出目录。', 'error');
      return false;
    }
    return runEngineCommand('extract', () => bridge.extract({
      partitions: uniqueNames, outputDir,
      options: { workers: preferences.workers, verify: preferences.verify, userAgent: preferences.userAgent },
    }));
  }, [available, bridge, busy, notify, outputDir, preferences, runEngineCommand, state.archive, state.partitions]);

  const cancel = useCallback(async () => {
    if (!bridge || cancelLock.current || !isEngineBusy(state.phase) || state.phase === 'cancelling') return;
    cancelLock.current = true;
    setCancelPending(true);
    try {
      await bridge.cancel();
      await refreshState();
    } catch (error) {
      notify(`取消失败：${errorMessage(error)}`, 'error');
    } finally {
      cancelLock.current = false;
      if (mounted.current) setCancelPending(false);
    }
  }, [bridge, notify, refreshState, state.phase]);

  const reset = useCallback(async () => {
    if (!bridge || !available || busy) return false;
    return runEngineCommand('reset', () => bridge.reset());
  }, [available, bridge, busy, runEngineCommand]);

  const pickFile = useCallback(async (): Promise<LocalFile | null> => {
    if (!bridge || busy || dialogLock.current) return null;
    dialogLock.current = true;
    setDialogBusy(true);
    try { return await bridge.pickFile(); }
    catch (error) { notify(`选择文件失败：${errorMessage(error)}`, 'error'); return null; }
    finally { dialogLock.current = false; if (mounted.current) setDialogBusy(false); }
  }, [bridge, busy, notify]);

  const fileFromDrop = useCallback((file: File): LocalFile | null => {
    if (!bridge || busy) return null;
    try {
      const path = bridge.pathForFile(file);
      if (!path) throw new Error('无法读取本地文件路径，请通过“选择文件”打开固件。');
      return { path, name: file.name, size: file.size };
    } catch (error) { notify(errorMessage(error), 'error'); return null; }
  }, [bridge, busy, notify]);

  const pickOutputDirectory = useCallback(async (): Promise<string | null> => {
    if (!bridge || busy || dialogLock.current) return null;
    dialogLock.current = true;
    setDialogBusy(true);
    try { return await bridge.pickOutputDir(); }
    catch (error) { notify(`选择输出目录失败：${errorMessage(error)}`, 'error'); return null; }
    finally { dialogLock.current = false; if (mounted.current) setDialogBusy(false); }
  }, [bridge, busy, notify]);

  const savePreferences = useCallback(async (next: Preferences, quiet = false) => {
    if (!bridge || preferencesLock.current) return false;
    const validation = validatePreferences(next);
    if (validation) { notify(validation, 'error'); return false; }
    preferencesLock.current = true;
    setSavingPreferences(true);
    try {
      const saved = await bridge.savePreferences(next);
      if (mounted.current) setPreferences(saved);
      if (!quiet) notify('设置已保存。', 'success');
      return true;
    } catch (error) {
      notify(`保存设置失败：${errorMessage(error)}`, 'error');
      return false;
    } finally {
      preferencesLock.current = false;
      if (mounted.current) setSavingPreferences(false);
    }
  }, [bridge, notify]);

  const chooseOutputDirectory = useCallback(async () => {
    if (!preferences || savingPreferences) return;
    const path = await pickOutputDirectory();
    if (path) await savePreferences({ ...preferences, outputDir: path }, true);
  }, [pickOutputDirectory, preferences, savePreferences, savingPreferences]);

  const openPath = useCallback(async (path: string) => {
    if (!bridge || !path) return;
    try { await bridge.openPath(path); }
    catch (error) { notify(`打开目录失败：${errorMessage(error)}`, 'error'); }
  }, [bridge, notify]);

  const reloadHistory = useCallback(async () => {
    if (!bridge || historyLock.current) return;
    const revision = historyRevision.current;
    setHistoryLoading(true);
    try {
      const entries = await bridge.getHistory();
      if (mounted.current && revision === historyRevision.current) {
        setHistory(entries);
        setHistoryLoaded(true);
      }
    } catch (error) { notify(`读取任务记录失败：${errorMessage(error)}`, 'error'); }
    finally { if (mounted.current) setHistoryLoading(false); }
  }, [bridge, notify]);

  const clearHistory = useCallback(async () => {
    if (!bridge || historyLock.current || busy) return false;
    historyLock.current = true;
    setClearingHistory(true);
    try {
      await bridge.clearHistory();
      const revision = historyRevision.current;
      const entries = await bridge.getHistory();
      if (mounted.current && revision === historyRevision.current) {
        setHistory(entries);
        setHistoryLoaded(true);
      }
      notify('任务记录已清空，已提取的文件不受影响。', 'success');
      return true;
    } catch (error) {
      notify(`清空任务记录失败：${errorMessage(error)}`, 'error');
      return false;
    } finally {
      historyLock.current = false;
      if (mounted.current) setClearingHistory(false);
    }
  }, [bridge, busy, notify]);

  const copyText = useCallback(async (text: string, label = '内容') => {
    try {
      if (bridge) await bridge.copyText(text);
      else await navigator.clipboard.writeText(text);
      notify(`${label}已复制。`, 'success');
    } catch (error) { notify(`复制失败：${errorMessage(error)}`, 'error'); }
  }, [bridge, notify]);

  return {
    bridge, appInfo, state, preferences, history, historyLoaded, logs, toasts, initialLoading, connectionError,
    pendingAction, cancelPending, savingPreferences, historyLoading, clearingHistory, dialogBusy,
    sourceDirty, sourceResetting, invalidateSource,
    busy, available, outputDir, bootstrap, parse, extract, cancel, reset, pickFile, fileFromDrop,
    pickOutputDirectory, chooseOutputDirectory, savePreferences, openPath, reloadHistory, clearHistory,
    copyText, notify, dismissToast, clearLogView: () => setLogs([]),
  };
}

export type DesktopController = ReturnType<typeof useFluxDesktop>;
