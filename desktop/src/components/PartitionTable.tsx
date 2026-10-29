import { useEffect, useMemo, useState } from 'react';
import { ArrowDownToLine, Check, CircleDashed, Download, FileBox, Layers3, Loader2, RotateCcw, Search, SearchX, X } from 'lucide-react';
import type { Partition } from '../types';
import type { DesktopController } from '../hooks/useFluxDesktop';
import { filterPartitions, formatBytes, partitionProgress, selectedPartitionBytes, toggleVisibleSelection } from '../utils';
import { Button, CardHeading, Checkbox, EmptyState, IconButton, ProgressBar, StatusBadge } from './Common';

function PartitionStatus({ partition }: { partition: Partition }) {
  const percent = partitionProgress(partition);
  const failed = partition.extractState === 'ERROR' || partition.verifyState === 'FAILED';
  let label = '待提取';
  let tone: 'neutral' | 'success' | 'warning' | 'error' | 'accent' = 'neutral';
  if (failed) { label = partition.verifyState === 'FAILED' ? '校验失败' : '提取失败'; tone = 'error'; }
  else if (partition.verifyState === 'VERIFYING') { label = '正在校验'; tone = 'accent'; }
  else if (partition.extractState === 'RUNNING') { label = '正在提取'; tone = 'accent'; }
  else if (partition.extractState === 'PAUSED') { label = '已暂停'; tone = 'warning'; }
  else if (partition.extractState === 'DONE') { label = '已完成'; tone = 'success'; }
  const showProgress = partition.extractState !== 'IDLE' || partition.verifyState === 'VERIFYING';
  const verifyingLabel = partition.verifyState === 'PASSED' ? 'SHA-256 通过'
    : partition.verifyState === 'SKIPPED' ? '未校验' : '';

  return <div className="partition-status">
    <div className="partition-status__label"><StatusBadge tone={tone} dot>{label}</StatusBadge>
      {showProgress && <span className="progress-number">{percent.toFixed(percent > 0 && percent < 1 ? 1 : 0)}%</span>}
    </div>
    {showProgress && <ProgressBar value={percent} label={`${partition.partitionName} 提取进度`} tone={failed ? 'error' : partition.extractState === 'DONE' ? 'success' : 'accent'} />}
    {partition.message ? <span className={`partition-message${failed ? ' text-error' : ''}`} title={partition.message}>{partition.message}</span>
      : verifyingLabel ? <span className="partition-message">{verifyingLabel}</span> : null}
  </div>;
}

export function PartitionTable({ desktop }: { desktop: DesktopController }) {
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const partitions = desktop.state.partitions;
  const archive = desktop.state.archive;
  const visible = useMemo(() => filterPartitions(partitions, query), [partitions, query]);
  const selectedBytes = useMemo(() => selectedPartitionBytes(partitions, selected), [partitions, selected]);
  const allVisibleSelected = visible.length > 0 && visible.every((partition) => selected.has(partition.partitionName));
  const someVisibleSelected = visible.some((partition) => selected.has(partition.partitionName));
  const canExtract = desktop.available && !desktop.busy && !desktop.sourceDirty && !desktop.sourceResetting && Boolean(archive) && Boolean(desktop.outputDir) && !desktop.savingPreferences;

  useEffect(() => {
    if (desktop.sourceDirty || desktop.state.phase === 'parsing' || !archive) setSelected(new Set());
  }, [archive, desktop.sourceDirty, desktop.state.phase]);
  useEffect(() => {
    const names = new Set(partitions.map((partition) => partition.partitionName));
    setSelected((current) => {
      const next = new Set([...current].filter((name) => names.has(name)));
      return next.size === current.size ? current : next;
    });
  }, [partitions]);

  function toggleOne(name: string) {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(name)) next.delete(name); else next.add(name);
      return next;
    });
  }

  return <section className="card partitions-card" aria-label="固件分区列表">
    <div className="partition-card-top"><CardHeading icon={Layers3} title="分区列表" action={<span className="count-badge">{archive ? partitions.length : '—'}</span>} />
      <div className="partition-card-tools"><div className="search-field"><Search size={16} aria-hidden="true" />
        <input aria-label="搜索分区" placeholder="搜索分区名称…" value={query} onChange={(event) => setQuery(event.target.value)} disabled={!partitions.length} />
        {query && <IconButton label="清除分区搜索" onClick={() => setQuery('')}><X size={13} /></IconButton>}
      </div><IconButton label="清除当前解析结果（不删除已提取文件）" disabled={!archive || desktop.busy || !desktop.available} onClick={() => void desktop.reset()}><RotateCcw size={16} /></IconButton></div>
    </div>
    {archive && <div className="parsed-source"><FileBox size={13} aria-hidden="true" /><span>{desktop.sourceDirty ? '旧来源 · 提取已禁用' : '当前固件'}</span><strong title={desktop.state.sourceLabel || archive.fileName}>{archive.fileName || desktop.state.sourceLabel || '—'}</strong></div>}
    <div className="table-scroll">
      <table className="partition-table"><caption className="sr-only">解析得到的固件分区、镜像大小、提取进度与操作</caption>
        <colgroup><col className="col-check" /><col /><col className="col-size" /><col className="col-status" /><col className="col-action" /></colgroup>
        <thead><tr><th scope="col"><Checkbox aria-label="全选当前可见分区" checked={allVisibleSelected} indeterminate={someVisibleSelected && !allVisibleSelected}
          disabled={!visible.length || desktop.busy} onChange={() => setSelected((current) => toggleVisibleSelection(current, visible))} /></th>
          <th scope="col">分区名称</th><th scope="col">镜像大小</th><th scope="col">状态 / 进度</th><th scope="col" className="align-right">操作</th></tr></thead>
        <tbody>{visible.map((partition) => <tr key={partition.partitionName} className={selected.has(partition.partitionName) ? 'is-selected' : ''}>
          <td><Checkbox aria-label={`选择分区 ${partition.partitionName}`} checked={selected.has(partition.partitionName)} disabled={desktop.busy} onChange={() => toggleOne(partition.partitionName)} /></td>
          <td><div className="partition-name-cell"><span className={`partition-file-icon${partition.extractState === 'DONE' && partition.verifyState !== 'FAILED' ? ' is-done' : ''}`}>
            {partition.extractState === 'DONE' && partition.verifyState !== 'FAILED' ? <Check size={17} /> : <FileBox size={17} />}</span>
            <div className="partition-name-text"><strong title={partition.partitionName}>{partition.partitionName}</strong>
              {(partition.subtitle || (partition.displayTitle && partition.displayTitle !== partition.partitionName)) && <span title={partition.subtitle || partition.displayTitle}>{partition.subtitle || partition.displayTitle}</span>}
            </div></div></td>
          <td className="size-cell"><span>{formatBytes(partition.size)}</span>{partition.compressedSize > 0 && partition.compressedSize !== partition.size && <small>压缩 {formatBytes(partition.compressedSize)}</small>}</td>
          <td><PartitionStatus partition={partition} /></td>
          <td className="align-right"><Button small variant="ghost" disabled={!canExtract} title={`${partition.extractState === 'DONE' ? '重新提取' : '提取'} ${partition.partitionName}`}
            onClick={() => void desktop.extract([partition.partitionName])}><ArrowDownToLine size={15} aria-hidden="true" />{partition.extractState === 'DONE' ? '重新提取' : '提取'}</Button></td>
        </tr>)}</tbody>
      </table>
    </div>
    {!visible.length && <div className={`partition-empty${desktop.state.phase === 'parsing' ? ' is-parsing' : ''}`}>
      {partitions.length ? <EmptyState icon={SearchX} title="没有匹配的分区" compact action={<Button small variant="ghost" onClick={() => setQuery('')}>清除搜索</Button>}>试试其他名称，或清除搜索查看全部分区。</EmptyState>
        : desktop.state.phase === 'parsing' ? <EmptyState icon={Loader2} title="正在读取固件分区" compact>{desktop.state.detail || '正在等待引擎返回分区列表，请稍候。'}</EmptyState>
          : archive ? <EmptyState icon={Layers3} title="没有可提取的分区" compact>引擎未从当前固件返回分区，请检查固件格式与运行日志。</EmptyState>
            : <EmptyState icon={Layers3} title="分区，在这里一目了然" compact>
              选择固件并解析后，即可按需提取所需镜像。
              <span className="empty-workflow"><span><span>1</span>选择固件</span><i /> <span><span>2</span>解析分区</span><i /> <span><span>3</span>提取镜像</span></span>
            </EmptyState>}
    </div>}
    <div className="partition-footer">
      <div className="selection-summary"><CircleDashed size={16} aria-hidden="true" />
        {archive ? <span>已选择 <strong>{selected.size}</strong> / {partitions.length} 个分区<span className="selection-size">{formatBytes(selectedBytes)}</span></span> : <span>解析后可选择分区</span>}
        {query && partitions.length > 0 && <span className="visible-count">当前显示 {visible.length} 项</span>}
      </div>
      <div className="inline-actions"><Button small disabled={!canExtract || !selected.size} onClick={() => void desktop.extract([...selected])}><Download size={15} aria-hidden="true" />提取选中{selected.size > 0 ? ` (${selected.size})` : ''}</Button>
        <Button small variant="primary" disabled={!canExtract || !partitions.length} onClick={() => void desktop.extract(partitions.map((partition) => partition.partitionName))}><ArrowDownToLine size={16} aria-hidden="true" />全部提取</Button>
      </div>
    </div>
  </section>;
}
