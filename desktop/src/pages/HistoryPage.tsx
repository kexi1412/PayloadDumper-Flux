import { useMemo, useState } from 'react';
import { ArrowUpRight, CheckCircle2, Clock3, FileArchive, FolderOpen, History, RefreshCw, Search, SearchX, Trash2, XCircle } from 'lucide-react';
import type { DesktopController } from '../hooks/useFluxDesktop';
import type { HistoryEntry } from '../types';
import { formatBytes, formatDateTime, formatDuration } from '../utils';
import { Button, ConfirmDialog, EmptyState, StatusBadge } from '../components/Common';

const STATUS = {
  completed: { label: '已完成', tone: 'success' as const },
  cancelled: { label: '已取消', tone: 'warning' as const },
  failed: { label: '失败', tone: 'error' as const },
};

function HistoryItem({ entry, desktop }: { entry: HistoryEntry; desktop: DesktopController }) {
  const status = STATUS[entry.status];
  return <article className="card history-item">
    <div className="history-item__heading"><span className={`history-file-icon history-file-icon--${entry.status}`}><FileArchive size={23} strokeWidth={1.6} aria-hidden="true" /></span>
      <div className="history-item__title"><h2 title={entry.archiveName}>{entry.archiveName || '未命名固件'}</h2><span>{formatDateTime(entry.startedAt)}<span className="bullet">·</span>耗时 {formatDuration(entry.startedAt, entry.finishedAt)}</span></div>
      <StatusBadge tone={status.tone} dot>{status.label}</StatusBadge>
    </div>
    <div className="history-item__metrics"><span><CheckCircle2 size={14} aria-hidden="true" />完成 <strong>{entry.completed}</strong> 个分区</span>
      <span><XCircle size={14} aria-hidden="true" />失败 <strong>{entry.failed}</strong> 个分区</span><span>任务大小 <strong>{formatBytes(entry.totalBytes)}</strong></span>
    </div>
    <div className="history-output"><FolderOpen size={15} aria-hidden="true" /><code title={entry.outputDir}>{entry.outputDir || '未记录输出目录'}</code>
      <Button small variant="ghost" disabled={!desktop.bridge || !entry.outputDir} onClick={() => void desktop.openPath(entry.outputDir)}>打开目录<ArrowUpRight size={14} aria-hidden="true" /></Button>
    </div>
    {entry.partitions.length > 0 && <details className="history-partitions"><summary>查看任务分区 <span>{entry.partitions.length}</span></summary>
      <div className="partition-tags">{entry.partitions.map((name, index) => <code key={`${name}-${index}`} title={name}>{name}</code>)}</div>
    </details>}
  </article>;
}

export function HistoryPage({ desktop }: { desktop: DesktopController }) {
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState('all');
  const [confirmClear, setConfirmClear] = useState(false);
  const entries = useMemo(() => desktop.history.filter((entry) => {
    const needle = query.trim().toLocaleLowerCase();
    return (status === 'all' || entry.status === status) && (!needle || [entry.archiveName, entry.outputDir, ...entry.partitions].some((value) => value.toLocaleLowerCase().includes(needle)));
  }).sort((left, right) => right.startedAt - left.startedAt), [desktop.history, query, status]);
  const finished = desktop.history.filter((entry) => entry.status === 'completed').length;
  const failed = desktop.history.filter((entry) => entry.status === 'failed').length;

  return <div className="page history-page">
    <div className="page-heading"><div><div className="eyebrow">EXTRACTION HISTORY</div><h1>任务记录</h1><p>回顾实际提取结果，快速定位输出文件。</p></div>
      <div className="inline-actions"><Button loading={desktop.historyLoading} disabled={!desktop.bridge} onClick={() => void desktop.reloadHistory()}>{!desktop.historyLoading && <RefreshCw size={15} />}刷新</Button>
        <Button disabled={!desktop.bridge || !desktop.history.length || desktop.busy || desktop.clearingHistory} onClick={() => setConfirmClear(true)}><Trash2 size={15} />清空记录</Button></div>
    </div>
    <section className="history-overview card" aria-label="历史任务统计"><div><span className="history-overview__icon"><History size={21} /></span><span>全部任务<strong>{desktop.historyLoaded ? desktop.history.length : '—'}</strong></span></div>
      <div><span className="history-overview__icon is-success"><CheckCircle2 size={21} /></span><span>已完成<strong>{desktop.historyLoaded ? finished : '—'}</strong></span></div>
      <div><span className="history-overview__icon is-error"><XCircle size={21} /></span><span>失败任务<strong>{desktop.historyLoaded ? failed : '—'}</strong></span></div>
    </section>
    <div className="history-toolbar"><div className="search-field"><Search size={17} aria-hidden="true" /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索固件、分区或输出目录…" aria-label="搜索任务记录" disabled={!desktop.history.length} /></div>
      <label className="history-filter"><span>任务状态</span><select aria-label="筛选任务状态" value={status} onChange={(event) => setStatus(event.target.value)}><option value="all">全部状态</option><option value="completed">已完成</option><option value="cancelled">已取消</option><option value="failed">失败</option></select></label>
    </div>
    {entries.length ? <div className="history-list">{entries.map((entry) => <HistoryItem key={entry.id} entry={entry} desktop={desktop} />)}</div>
      : <section className="card history-empty"><EmptyState icon={desktop.historyLoading ? Clock3 : desktop.history.length ? SearchX : History}
        title={desktop.historyLoading ? '正在读取任务记录' : !desktop.historyLoaded && desktop.bridge ? '任务记录暂不可用' : desktop.history.length ? '没有匹配的任务' : '还没有提取记录'}
        action={desktop.history.length ? <Button small onClick={() => { setQuery(''); setStatus('all'); }}>清除筛选</Button> : undefined}>
        {!desktop.bridge ? '任务记录由桌面应用保存在本地。连接桌面服务后可查看。' : desktop.historyLoading ? '等待桌面服务返回本地任务历史。' : desktop.history.length ? '调整关键词或任务状态后再试。' : '实际执行提取后，任务结果与输出位置会自动出现在这里。'}
      </EmptyState></section>}
    <p className="page-note">清空记录只移除历史索引，不会删除已提取的镜像文件。</p>
    <ConfirmDialog open={confirmClear} title="清空所有任务记录？" confirmLabel="确认清空" busy={desktop.clearingHistory}
      onClose={() => setConfirmClear(false)} onConfirm={() => { void desktop.clearHistory().then((success) => { if (success) setConfirmClear(false); }); }}>
      将移除本地保存的 {desktop.history.length} 条任务记录。已提取到输出目录的文件会保留，此操作不能恢复任务记录。
    </ConfirmDialog>
  </div>;
}
