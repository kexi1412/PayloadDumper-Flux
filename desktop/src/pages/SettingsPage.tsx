import { useEffect, useRef, useState } from 'react';
import { Check, Cpu, FolderInput, FolderOpen, Globe2, Info, Monitor, Moon, Palette, RotateCcw, Save, Settings2, ShieldCheck, Sun } from 'lucide-react';
import type { Preferences } from '../types';
import type { DesktopController } from '../hooks/useFluxDesktop';
import { samePreferences, validatePreferences } from '../utils';
import { Button, CardHeading, EmptyState, StatusBadge } from '../components/Common';

const THEMES = [
  { value: 'light' as const, label: '浅色', description: '清爽明亮', icon: Sun },
  { value: 'dark' as const, label: '深色', description: '专注低光环境', icon: Moon },
  { value: 'system' as const, label: '跟随系统', description: '自动匹配系统外观', icon: Monitor },
];

export function SettingsPage({ desktop }: { desktop: DesktopController }) {
  const [draft, setDraft] = useState<Preferences | null>(desktop.preferences);
  const previous = useRef<Preferences | null>(desktop.preferences);
  const applying = useRef(false);

  // External theme/output changes do not silently discard unsaved, unrelated fields.
  useEffect(() => {
    const next = desktop.preferences;
    const baseline = previous.current;
    if (!next) return;
    const acceptSaved = applying.current;
    setDraft((current) => {
      if (!current || !baseline || acceptSaved) return next;
      return {
        theme: current.theme === baseline.theme ? next.theme : current.theme,
        workers: current.workers === baseline.workers ? next.workers : current.workers,
        verify: current.verify === baseline.verify ? next.verify : current.verify,
        userAgent: current.userAgent === baseline.userAgent ? next.userAgent : current.userAgent,
        outputDir: current.outputDir === baseline.outputDir ? next.outputDir : current.outputDir,
      };
    });
    previous.current = next;
    applying.current = false;
  }, [desktop.preferences]);

  const dirty = Boolean(draft && desktop.preferences && !samePreferences(draft, desktop.preferences));
  const locked = desktop.busy || desktop.savingPreferences || desktop.initialLoading;
  const validation = draft ? validatePreferences(draft) : null;

  function update<K extends keyof Preferences>(key: K, value: Preferences[K]) {
    setDraft((current) => current ? { ...current, [key]: value } : current);
  }

  async function save() {
    if (!draft || locked) return;
    applying.current = true;
    const success = await desktop.savePreferences(draft);
    if (!success) applying.current = false;
  }

  return <div className="page settings-page">
    <div className="page-heading"><div><div className="eyebrow">PREFERENCES</div><h1>设置</h1><p>让提取方式与桌面环境更适合你。</p></div>
      {dirty && <StatusBadge tone="warning" dot>有未保存更改</StatusBadge>}
    </div>
    {!draft ? <section className="card"><EmptyState icon={Settings2} title={desktop.initialLoading ? '正在读取设置' : '桌面设置暂不可用'}>
      {desktop.bridge ? '设置需要由桌面服务读取。请检查连接状态后重试。' : '请从 Flux 桌面应用打开此页面，读取和保存真实偏好设置。'}
    </EmptyState></section> : <>
      <section className="card settings-card"><CardHeading icon={Palette} title="外观" description="选择工作台的显示主题" />
        <div className="theme-options" role="group" aria-label="外观主题">{THEMES.map(({ value, label, description, icon: Icon }) => <button type="button" key={value}
          className={`theme-option${draft.theme === value ? ' is-selected' : ''}`} aria-pressed={draft.theme === value} disabled={locked} onClick={() => update('theme', value)}>
          <span className={`theme-preview theme-preview--${value}`} aria-hidden="true"><span className="theme-preview__sidebar" /><span className="theme-preview__body"><i /><span><i /><i /><i /></span><i /></span></span>
          <span className="theme-option__label"><Icon size={16} aria-hidden="true" /><strong>{label}</strong>{draft.theme === value && <Check size={16} className="theme-selected-check" aria-hidden="true" />}</span>
          <span className="theme-option__description">{description}</span>
        </button>)}</div>
      </section>
      <div className="settings-grid"><section className="card settings-card"><CardHeading icon={Cpu} title="提取选项" description="用于后续解析和提取请求" />
        <div className="setting-row setting-row--vertical"><label htmlFor="worker-count"><strong>并行线程</strong><span>在速度与资源占用之间取得平衡</span></label>
          <div className="workers-control"><input type="range" min={1} max={16} step={1} value={Number.isFinite(draft.workers) ? draft.workers : 1}
            aria-label="并行线程滑块" disabled={locked} onChange={(event) => update('workers', Number(event.target.value))} />
            <input id="worker-count" type="number" min={1} max={16} step={1} inputMode="numeric" value={Number.isFinite(draft.workers) ? draft.workers : ''}
              disabled={locked} aria-invalid={!(Number.isInteger(draft.workers) && draft.workers >= 1 && draft.workers <= 16)} onChange={(event) => update('workers', event.target.valueAsNumber)} />
          </div><div className="range-labels"><span>1 线程</span><span>16 线程</span></div>
        </div>
        <div className="setting-row"><div><label id="verify-label"><strong>校验分区哈希</strong></label><p id="verify-description">使用固件提供的 SHA-256 检查提取结果；缺失哈希时由引擎报告。</p></div>
          <button type="button" role="switch" className={`switch${draft.verify ? ' is-checked' : ''}`} aria-checked={draft.verify} aria-labelledby="verify-label" aria-describedby="verify-description"
            disabled={locked} onClick={() => update('verify', !draft.verify)}><span /></button>
        </div>
        <div className="settings-tip"><ShieldCheck size={16} aria-hidden="true" /><span>提取完成与哈希校验结果会分别显示在分区状态中。</span></div>
      </section>
      <section className="card settings-card"><CardHeading icon={Globe2} title="网络请求" description="用于 HTTP / HTTPS 固件直链" />
        <div className="setting-row setting-row--vertical"><label htmlFor="user-agent"><strong>User-Agent</strong><span>向固件下载服务器发送的客户端标识</span></label>
          <textarea id="user-agent" className="user-agent-input" rows={3} value={draft.userAgent} disabled={locked} spellCheck={false} autoComplete="off"
            placeholder="留空使用引擎默认 User-Agent" onChange={(event) => update('userAgent', event.target.value)} aria-describedby="user-agent-help" />
          <p id="user-agent-help" className="field-hint">只用于网络来源，不影响本地文件读取。不要包含换行符。</p>
        </div>
        <div className="settings-tip"><Info size={16} aria-hidden="true" /><span>网络固件需要可直接访问、支持 HTTP Range 的下载地址。</span></div>
      </section></div>
      <section className="card settings-card"><CardHeading icon={FolderOpen} title="输出位置" description="新的提取任务将使用此目录" />
        <div className="settings-output"><code title={draft.outputDir}>{draft.outputDir || '尚未选择输出目录'}</code>
          <Button disabled={locked || desktop.dialogBusy} onClick={() => { void desktop.pickOutputDirectory().then((path) => { if (path) update('outputDir', path); }); }}><FolderInput size={16} />选择目录</Button>
          <Button disabled={!desktop.bridge || !draft.outputDir} onClick={() => void desktop.openPath(draft.outputDir)}><FolderOpen size={16} />打开</Button>
        </div>
      </section>
      <div className="settings-save-bar"><div>{validation && dirty ? <p className="field-error" role="alert">{validation}</p> : <span className="settings-save-note">{desktop.busy ? '任务运行期间不能修改提取配置。' : '设置在桌面服务保存成功后生效。'}</span>}</div>
        <div className="inline-actions"><Button disabled={!dirty || locked} onClick={() => setDraft(desktop.preferences)}><RotateCcw size={15} />放弃更改</Button>
          <Button variant="primary" loading={desktop.savingPreferences} disabled={!dirty || locked || Boolean(validation)} onClick={() => void save()}>{!desktop.savingPreferences && <Save size={16} />}保存设置</Button>
        </div>
      </div>
    </>}
    <section className="about-strip"><span className="about-logo"><LayersLogo /></span><div><strong>Payload Dumper · Flux</strong><span>Android 固件提取器 · 桌面版</span></div>
      <span className="about-version">{desktop.appInfo ? `v${desktop.appInfo.version}` : '版本 —'}<span>{desktop.appInfo?.platform || '尚未连接桌面服务'}</span></span>
    </section>
  </div>;
}

function LayersLogo() {
  return <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="m12 3 9 5-9 5-9-5 9-5Z" stroke="currentColor" strokeWidth="1.7" strokeLinejoin="round" /><path d="m3 12 9 5 9-5M3 16l9 5 9-5" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" /></svg>;
}
