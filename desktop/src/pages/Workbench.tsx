import { Activity, AlertCircle, ArrowUpRight, CheckCircle2, Cpu, FileArchive, FolderInput, FolderOpen, HardDrive, Info, Layers3, Loader2, Monitor, RefreshCw } from 'lucide-react';
import type { DesktopController } from '../hooks/useFluxDesktop';
import { formatBytes, overallProgress } from '../utils';
import { Button, PHASE_LABELS, ProgressBar, StatusBadge } from '../components/Common';
import { FirmwareCard } from '../components/FirmwareCard';
import { PartitionTable } from '../components/PartitionTable';
import { SourceCard, type SourceSelection, type SourceMode } from '../components/SourceCard';

interface WorkbenchProps {
  desktop: DesktopController;
  source: SourceSelection;
  onModeChange: (mode: SourceMode) => void;
  onUrlChange: (url: string) => void;
  onPickFile: () => void;
  onDropFile: (file: File) => void;
  onParse: () => void;
}

function ConnectionNotice({ desktop }: { desktop: DesktopController }) {
  if (!desktop.bridge) return <div className="notice notice--info" role="status"><Monitor size={20} aria-hidden="true" />
    <div><strong>这是 Flux 桌面应用，不是在线演示</strong><p>当前网页未连接桌面服务。请通过桌面应用打开，连接本地文件选择器与固件提取引擎。</p></div>
  </div>;
  if (desktop.initialLoading) return <div className="notice notice--info" role="status"><Loader2 size={19} className="spin" aria-hidden="true" />
    <div><strong>正在连接桌面服务</strong><p>正在读取真实引擎状态、偏好设置和任务记录。</p></div>
  </div>;
  if (desktop.connectionError || !desktop.appInfo?.engineReady) return <div className="notice notice--warning" role="alert"><AlertCircle size={20} aria-hidden="true" />
    <div><strong>{desktop.connectionError ? '桌面服务连接异常' : '固件提取引擎尚未就绪'}</strong><p>{desktop.connectionError || desktop.state.detail || '后端未成功启动，解析与提取暂不可用。请查看运行日志，并重新检查连接。'}</p></div>
    <Button small onClick={() => void desktop.bootstrap()} disabled={desktop.busy}><RefreshCw size={14} />重新检查</Button>
  </div>;
  if (desktop.state.phase === 'error') return <div className="notice notice--error" role="alert"><AlertCircle size={20} aria-hidden="true" />
    <div><strong>当前操作未完成</strong><p>{desktop.state.detail || '引擎报告错误，请检查来源并查看运行日志。'}</p></div>
  </div>;
  return null;
}

export function Workbench({ desktop, source, ...sourceActions }: WorkbenchProps) {
  const archive = desktop.state.archive;
  const completed = desktop.state.partitions.filter((partition) => partition.extractState === 'DONE' && partition.verifyState !== 'FAILED').length;
  const metrics = [
    { title: '固件大小', value: archive && archive.fileSize > 0 ? formatBytes(archive.fileSize) : '—', description: archive?.fileName || '等待解析固件', icon: HardDrive, color: 'blue' },
    { title: '分区数量', value: archive ? archive.partitionCount.toLocaleString('zh-CN') : '—', description: archive ? '来自当前固件的分区列表' : '解析后自动识别', icon: Layers3, color: 'violet' },
    { title: '已完成', value: archive ? completed.toLocaleString('zh-CN') : '—', description: archive ? `${desktop.state.partitions.length} 个分区中的已完成项` : '镜像提取进度实时同步', icon: CheckCircle2, color: 'green' },
    { title: '并行线程', value: desktop.preferences ? String(desktop.preferences.workers) : '—', description: desktop.preferences ? '可在设置中调整 · 1–16' : '等待读取引擎配置', icon: Cpu, color: 'amber' },
  ];
  const taskRunning = desktop.state.phase === 'extracting' || desktop.state.phase === 'cancelling';
  const progress = overallProgress(desktop.state.partitions);

  return <div className="page workbench-page">
    <div className="page-heading"><div><div className="eyebrow">FIRMWARE WORKSPACE</div><h1>固件工作台</h1><p>解析 Android 固件，按需提取分区镜像。</p></div>
      <div className="page-heading__actions"><span className="desktop-only keyboard-hint"><kbd>Ctrl</kbd><span>+</span><kbd>O</kbd><span>选择文件</span></span>
        <StatusBadge tone={desktop.available && desktop.state.phase !== 'idle' ? 'accent' : 'neutral'} dot>{desktop.available ? PHASE_LABELS[desktop.state.phase] : desktop.initialLoading ? '正在连接' : '未连接引擎'}</StatusBadge>
      </div>
    </div>
    <ConnectionNotice desktop={desktop} />
    <section className="metrics-grid" aria-label="当前固件真实指标">{metrics.map(({ title, value, description, icon: Icon, color }) => <article className="card metric-card" key={title}>
      <div className="metric-card__content"><span className="metric-label">{title}</span><strong className={value === '—' ? 'metric-value is-unknown' : 'metric-value'}>{value}</strong><span className="metric-description" title={description}>{description}</span></div>
      <span className={`metric-icon metric-icon--${color}`}><Icon size={21} strokeWidth={1.7} aria-hidden="true" /></span>
    </article>)}</section>
    <div className="source-info-grid"><SourceCard desktop={desktop} source={source} {...sourceActions} /><FirmwareCard desktop={desktop} /></div>
    {taskRunning && <section className="task-progress-card" aria-label="提取任务进度"><div className="task-progress-card__heading"><span className="task-progress-icon"><Activity size={20} aria-hidden="true" /></span>
      <div><strong>{desktop.state.phase === 'cancelling' ? '正在取消任务' : '正在提取分区镜像'}</strong><span>{desktop.state.detail || '真实提取进度由本地引擎持续返回'}</span></div><span className="task-progress-percent">{progress.toFixed(0)}%</span>
    </div><ProgressBar value={progress} label="全部分区的字节加权进度" /><p>已完成 {completed} / {desktop.state.partitions.length} 个分区 · 此进度以当前全部分区大小加权计算</p></section>}
    <div className="results-section"><div className="output-bar"><div className="output-bar__icon"><FolderOpen size={19} aria-hidden="true" /></div>
      <div className="output-bar__path"><span>输出目录</span><code title={desktop.outputDir || '尚未读取输出目录'}>{desktop.outputDir || '连接桌面服务后选择输出目录'}</code></div>
      <div className="inline-actions"><Button small variant="ghost" disabled={!desktop.preferences || desktop.busy || desktop.dialogBusy || desktop.savingPreferences} onClick={() => void desktop.chooseOutputDirectory()}><FolderInput size={15} aria-hidden="true" />更改</Button>
        <Button small disabled={!desktop.bridge || !desktop.outputDir} onClick={() => void desktop.openPath(desktop.outputDir)}><ArrowUpRight size={15} aria-hidden="true" />打开目录</Button></div>
    </div><PartitionTable desktop={desktop} /></div>
    <div className="workspace-footnote"><span><Info size={13} aria-hidden="true" />仅显示引擎返回的信息，未知元数据保留为空。</span><span><FileArchive size={13} aria-hidden="true" />Payload Dumper · Flux</span></div>
  </div>;
}
