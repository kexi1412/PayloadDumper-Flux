import { useCallback, useEffect, useRef, useState } from 'react';
import { ChevronRight, CircleHelp, HardDriveDownload, History, Layers3, Monitor, Moon, PanelLeftClose, PanelLeftOpen, Settings2, Sun, Terminal } from 'lucide-react';
import { IconButton, PHASE_LABELS, ToastRegion } from './components/Common';
import { LogPanel } from './components/LogPanel';
import type { SourceSelection } from './components/SourceCard';
import { useFluxDesktop } from './hooks/useFluxDesktop';
import { HistoryPage } from './pages/HistoryPage';
import { SettingsPage } from './pages/SettingsPage';
import { Workbench } from './pages/Workbench';
import type { Preferences } from './types';

type Page = 'workbench' | 'history' | 'settings';
const NAVIGATION = [
  { id: 'workbench' as const, label: '固件提取', icon: HardDriveDownload },
  { id: 'history' as const, label: '任务记录', icon: History },
  { id: 'settings' as const, label: '设置', icon: Settings2 },
];
const THEME_LABELS = { light: '浅色', dark: '深色', system: '跟随系统' };

function useDocumentTheme(theme: Preferences['theme']) {
  useEffect(() => {
    const query = window.matchMedia('(prefers-color-scheme: dark)');
    const apply = () => {
      const resolved = theme === 'system' ? (query.matches ? 'dark' : 'light') : theme;
      document.documentElement.dataset.theme = resolved;
      document.documentElement.style.colorScheme = resolved;
    };
    apply();
    query.addEventListener('change', apply);
    return () => query.removeEventListener('change', apply);
  }, [theme]);
}

export default function App() {
  const desktop = useFluxDesktop();
  const [page, setPage] = useState<Page>('workbench');
  const [collapsed, setCollapsed] = useState(false);
  const [logOpen, setLogOpen] = useState(false);
  const [source, setSource] = useState<SourceSelection>({ mode: 'file', file: null, url: '' });
  const contentRef = useRef<HTMLElement>(null);
  const logRef = useRef<HTMLDivElement>(null);
  const theme = desktop.preferences?.theme ?? 'system';
  useDocumentTheme(theme);

  const pickFile = useCallback(async () => {
    if (!desktop.bridge || desktop.busy || desktop.dialogBusy || desktop.initialLoading) return;
    const file = await desktop.pickFile();
    if (file) {
      if (source.mode !== 'file' || source.file?.path !== file.path || source.file?.size !== file.size) void desktop.invalidateSource();
      setSource((current) => ({ ...current, mode: 'file', file }));
      setPage('workbench');
    }
  }, [desktop.bridge, desktop.busy, desktop.dialogBusy, desktop.initialLoading, desktop.invalidateSource, desktop.pickFile, source.mode, source.file]);

  const parse = useCallback(() => {
    if (source.mode === 'file' && !source.file) { desktop.notify('请先选择本地固件文件。', 'error'); return; }
    void desktop.parse({ kind: source.mode, value: source.mode === 'file' ? source.file!.path : source.url });
  }, [desktop.notify, desktop.parse, source]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || event.altKey || event.repeat || document.querySelector('dialog[open]')) return;
      if (event.key.toLowerCase() === 'o') { event.preventDefault(); void pickFile(); }
      else if (event.key === 'Enter' && page === 'workbench') { event.preventDefault(); if (desktop.available && !desktop.busy) parse(); }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [desktop.available, desktop.busy, page, parse, pickFile]);

  useEffect(() => {
    const preventFileNavigation = (event: DragEvent) => {
      if (event.dataTransfer?.types.includes('Files')) event.preventDefault();
    };
    window.addEventListener('dragover', preventFileNavigation);
    window.addEventListener('drop', preventFileNavigation);
    return () => { window.removeEventListener('dragover', preventFileNavigation); window.removeEventListener('drop', preventFileNavigation); };
  }, []);

  useEffect(() => { contentRef.current?.scrollTo({ top: 0 }); }, [page]);

  function cycleTheme() {
    if (!desktop.preferences) return;
    const order: Preferences['theme'][] = ['system', 'light', 'dark'];
    const next = order[(order.indexOf(theme) + 1) % order.length];
    void desktop.savePreferences({ ...desktop.preferences, theme: next }, true);
  }

  function showLogs() {
    setLogOpen(true);
    window.requestAnimationFrame(() => logRef.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' }));
  }

  const statusText = !desktop.bridge ? '桌面服务未连接' : desktop.initialLoading ? '正在连接桌面服务'
    : desktop.connectionError ? '服务连接异常' : !desktop.appInfo?.engineReady ? '提取引擎未就绪'
      : desktop.state.phase === 'idle' ? '引擎已就绪' : PHASE_LABELS[desktop.state.phase];
  const statusTone = desktop.available ? (desktop.state.phase === 'error' ? 'error' : 'success') : desktop.bridge ? 'warning' : 'neutral';
  const activeLabel = page === 'workbench' ? '固件工作台' : page === 'history' ? '任务记录' : '设置';
  const ThemeIcon = theme === 'dark' ? Moon : theme === 'light' ? Sun : Monitor;

  return <div className={`app-shell${collapsed ? ' is-collapsed' : ''}`}>
    <a className="skip-link" href="#main-content">跳转到主要内容</a>
    <aside className="sidebar" aria-label="主导航">
      <div className="brand" title="Payload Dumper · Flux"><span className="brand-mark"><Layers3 size={23} strokeWidth={1.8} aria-hidden="true" /></span>
        <div className="brand-copy"><div><strong>Flux</strong><span>DESKTOP</span></div><small>PAYLOAD DUMPER</small></div>
      </div>
      <div className="sidebar-section-label">工作空间</div>
      <nav className="navigation">{NAVIGATION.map(({ id, label, icon: Icon }) => <button type="button" key={id} className={`nav-item${page === id ? ' is-active' : ''}`}
        onClick={() => setPage(id)} aria-current={page === id ? 'page' : undefined} aria-label={label} title={label}>
        <Icon size={19} strokeWidth={1.8} aria-hidden="true" /><span className="nav-label">{label}</span>
        {id === 'history' && desktop.history.length > 0 && <span className="nav-count">{desktop.history.length}</span>}
      </button>)}</nav>
      <div className="sidebar-bottom">
        <div className="sidebar-product"><span className="sidebar-product__icon"><HardDriveDownload size={20} strokeWidth={1.6} aria-hidden="true" /></span>
          <strong>你的固件，由你掌控</strong><p>读取固件，提取所需镜像。<br />每一次操作，清晰可见。</p><span>ANDROID FIRMWARE TOOLS</span>
        </div>
        <button type="button" className={`runtime-status runtime-status--${statusTone}`} onClick={showLogs} title={`${statusText} · 查看运行日志`} aria-label={`${statusText}，查看运行日志`}>
          <span className={`runtime-status__dot${desktop.busy ? ' is-pulsing' : ''}`} /><span className="runtime-status__copy"><strong>本地引擎</strong><span>{statusText}</span></span><ChevronRight size={14} className="runtime-status__arrow" aria-hidden="true" />
        </button>
        <span className="sidebar-bottom-brand">Payload Dumper · Flux</span>
      </div>
    </aside>
    <div className="app-body"><header className="topbar"><div className="topbar__left">
      <IconButton label={collapsed ? '展开侧边栏' : '收起侧边栏'} aria-expanded={!collapsed} onClick={() => setCollapsed((value) => !value)}>{collapsed ? <PanelLeftOpen size={19} /> : <PanelLeftClose size={19} />}</IconButton>
      <nav className="breadcrumb" aria-label="面包屑"><span>工作空间</span><ChevronRight size={14} aria-hidden="true" /><strong aria-current="page">{activeLabel}</strong></nav>
    </div><div className="topbar__right"><span className="version-tag">{desktop.appInfo ? `v${desktop.appInfo.version}` : '桌面版'}</span><span className="topbar-divider" />
      <IconButton label={`切换主题，当前${THEME_LABELS[theme]}`} onClick={cycleTheme} disabled={!desktop.preferences || desktop.savingPreferences} title={`当前${THEME_LABELS[theme]} · 点击切换（浅色 / 深色 / 跟随系统）`}><ThemeIcon size={18} /></IconButton>
      <IconButton label="查看运行日志" onClick={showLogs}><Terminal size={18} /></IconButton>
      <span className="topbar-divider" /><span className="desktop-label"><Monitor size={15} aria-hidden="true" />桌面工作台</span>
    </div></header>
      <main id="main-content" className="main-content" ref={contentRef} tabIndex={-1}>
        <div hidden={page !== 'workbench'}><Workbench desktop={desktop} source={source}
          onModeChange={(mode) => {
            if (source.mode !== mode) void desktop.invalidateSource();
            setSource((current) => ({ ...current, mode }));
          }} onUrlChange={(url) => {
            if (source.mode === 'url' && source.url.trim() !== url.trim()) void desktop.invalidateSource();
            setSource((current) => ({ ...current, url }));
          }}
          onPickFile={() => void pickFile()} onDropFile={(file) => {
            const local = desktop.fileFromDrop(file);
            if (!local) return;
            if (source.mode !== 'file' || source.file?.path !== local.path || source.file?.size !== local.size) void desktop.invalidateSource();
            setSource((current) => ({ ...current, mode: 'file', file: local }));
          }} onParse={parse} /></div>
        <div hidden={page !== 'history'}><HistoryPage desktop={desktop} /></div>
        <div hidden={page !== 'settings'}><SettingsPage desktop={desktop} /></div>
        <div ref={logRef}><LogPanel desktop={desktop} open={logOpen} onToggle={() => setLogOpen((value) => !value)} /></div>
        <footer className="app-footer"><span>Payload Dumper · Flux <span className="bullet">/</span> Android 固件提取器</span>
          <span className="footer-shortcuts"><CircleHelp size={13} aria-hidden="true" /><kbd>Ctrl</kbd> + <kbd>O</kbd> 选择文件 <span className="bullet">·</span> <kbd>Ctrl</kbd> + <kbd>Enter</kbd> 解析</span>
        </footer>
      </main>
    </div>
    <ToastRegion toasts={desktop.toasts} onDismiss={desktop.dismissToast} />
  </div>;
}
