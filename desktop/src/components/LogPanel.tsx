import { useEffect, useRef, useState } from 'react';
import { ChevronDown, ChevronUp, Copy, Terminal, Trash2 } from 'lucide-react';
import type { DesktopController } from '../hooks/useFluxDesktop';
import { formatLogTime } from '../utils';
import { Button, IconButton } from './Common';

export function LogPanel({ desktop, open, onToggle }: { desktop: DesktopController; open: boolean; onToggle: () => void }) {
  const [filter, setFilter] = useState<'all' | 'error'>('all');
  const [follow, setFollow] = useState(true);
  const scrollRef = useRef<HTMLDivElement>(null);
  const visibleLogs = desktop.logs.filter((entry) => filter === 'all' || entry.level === 'error');
  const errorCount = desktop.logs.filter((entry) => entry.level === 'error').length;
  useEffect(() => {
    const element = scrollRef.current;
    if (open && follow && element) element.scrollTop = element.scrollHeight;
  }, [desktop.logs.length, filter, follow, open]);

  return <section className={`card log-panel${open ? ' log-panel--open' : ''}`} aria-label="运行日志">
    <div className="log-panel__bar">
      <button type="button" className="log-panel__toggle" onClick={onToggle} aria-expanded={open} aria-controls="runtime-log-content">
        <Terminal size={17} aria-hidden="true" /><span>运行日志</span><span className="count-badge">{desktop.logs.length}</span>
        {errorCount > 0 && <span className="log-error-count">{errorCount} 条错误</span>}
      </button>
      <div className="log-panel__actions"><span className="log-source-label">{desktop.bridge ? '来自本次桌面会话' : '等待桌面服务连接'}</span>
        <IconButton label={open ? '收起运行日志' : '展开运行日志'} onClick={onToggle} aria-expanded={open} aria-controls="runtime-log-content">
          {open ? <ChevronUp size={17} /> : <ChevronDown size={17} />}
        </IconButton>
      </div>
    </div>
    {open && <div id="runtime-log-content" className="log-panel__content">
      <div className="log-toolbar">
        <div className="filter-tabs" role="group" aria-label="日志级别">
          <button type="button" className={filter === 'all' ? 'is-active' : ''} onClick={() => setFilter('all')} aria-pressed={filter === 'all'}>全部</button>
          <button type="button" className={filter === 'error' ? 'is-active' : ''} onClick={() => setFilter('error')} aria-pressed={filter === 'error'}>仅错误</button>
        </div>
        <div className="inline-actions"><label className="follow-toggle"><input type="checkbox" checked={follow} onChange={(event) => setFollow(event.target.checked)} />跟随最新</label>
          <IconButton label="复制当前日志" disabled={!visibleLogs.length} onClick={() => void desktop.copyText(visibleLogs.map((entry) => `${formatLogTime(entry.time)} [${entry.level.toUpperCase()}] ${entry.message}`).join('\n'), '日志')}><Copy size={15} /></IconButton>
          <IconButton label="清空日志视图（不删除任务记录）" disabled={!desktop.logs.length} onClick={desktop.clearLogView}><Trash2 size={15} /></IconButton>
        </div>
      </div>
      <div className="log-entries" ref={scrollRef} role="log" aria-label="引擎事件日志" aria-live="off" tabIndex={0}>
        {visibleLogs.length ? visibleLogs.map((entry, index) => <div className={`log-entry log-entry--${entry.level}`} key={`${entry.time}-${index}`}>
          <time>{formatLogTime(entry.time)}</time><span className="log-entry__level">{entry.level.toUpperCase()}</span><span>{entry.message}</span>
        </div>) : <div className="log-empty"><Terminal size={20} aria-hidden="true" /><p>{filter === 'error' ? '当前会话没有错误日志。' : '尚无运行日志。解析与提取时，真实引擎事件会显示在这里。'}</p></div>}
      </div>
      <div className="log-panel__footer"><span>仅保留最近 500 条事件</span><Button small variant="ghost" onClick={onToggle}>收起日志 <ChevronUp size={14} /></Button></div>
    </div>}
  </section>;
}
