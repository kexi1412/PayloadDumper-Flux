import type { EnginePhase, Partition, Preferences } from './types';

/** Binary units match the partition byte counts reported by the extraction engine. */
export function formatBytes(bytes: number | null | undefined, decimals = 2): string {
  if (bytes == null || !Number.isFinite(bytes) || bytes < 0) return '—';
  if (bytes === 0) return '0 B';
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB'];
  const exponent = Math.max(0, Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1));
  const precision = exponent === 0 ? 0 : Math.max(0, Math.min(4, decimals));
  return `${(bytes / 1024 ** exponent).toLocaleString('zh-CN', {
    maximumFractionDigits: precision,
  })} ${units[exponent]}`;
}

export function metadataValue(value: string | number | null | undefined): string {
  if (value == null || value === '' || (typeof value === 'number' && (!Number.isFinite(value) || value <= 0))) return '—';
  const text = String(value).trim();
  if (!text || /^(unknown|n\/a|null|undefined|not available)$/i.test(text)) return '—';
  return text;
}

export function sourceBasename(value: string): string {
  try {
    if (/^https?:\/\//i.test(value)) {
      const url = new URL(value);
      const name = url.pathname.split('/').filter(Boolean).pop();
      return name ? decodeURIComponent(name) : url.hostname;
    }
  } catch { /* Preserve the original path if a server supplied malformed escaping. */ }
  return value.split(/[\\/]/).filter(Boolean).pop() || value;
}

export function validateSourceUrl(value: string): string | null {
  if (!value.trim()) return '请输入固件的 HTTP 或 HTTPS 直链。';
  try {
    const url = new URL(value.trim());
    if (!['http:', 'https:'].includes(url.protocol) || !url.hostname) return '仅支持 HTTP 或 HTTPS 固件直链。';
    return null;
  } catch {
    return '链接格式不正确，请输入完整的 https:// 地址。';
  }
}

export function isEngineBusy(phase: EnginePhase): boolean {
  return phase === 'parsing' || phase === 'extracting' || phase === 'cancelling';
}

/** Engine progress is a 0..1 ratio, never a locally simulated timer. */
export function progressPercent(progress: number): number {
  return Number.isFinite(progress) ? Math.min(100, Math.max(0, progress * 100)) : 0;
}

export function partitionProgress(partition: Partition): number {
  return partition.extractState === 'DONE' ? 100 : progressPercent(partition.progress);
}

export function filterPartitions(partitions: Partition[], query: string): Partition[] {
  const needle = query.trim().toLocaleLowerCase();
  if (!needle) return partitions;
  return partitions.filter((partition) =>
    [partition.partitionName, partition.displayTitle, partition.subtitle]
      .some((value) => value.toLocaleLowerCase().includes(needle)),
  );
}

export function selectedPartitionBytes(partitions: Partition[], selection: ReadonlySet<string>): number {
  return partitions.reduce((total, partition) =>
    total + (selection.has(partition.partitionName) && Number.isFinite(partition.size) && partition.size > 0 ? partition.size : 0), 0);
}

export function toggleVisibleSelection(current: ReadonlySet<string>, visible: Partition[]): Set<string> {
  const next = new Set(current);
  const remove = visible.length > 0 && visible.every((partition) => next.has(partition.partitionName));
  for (const partition of visible) {
    if (remove) next.delete(partition.partitionName);
    else next.add(partition.partitionName);
  }
  return next;
}

export function overallProgress(partitions: Partition[]): number {
  if (partitions.length === 0) return 0;
  const totalBytes = partitions.reduce((sum, partition) => sum + Math.max(0, partition.size || 0), 0);
  if (!totalBytes) return partitions.reduce((sum, partition) => sum + partitionProgress(partition), 0) / partitions.length;
  return partitions.reduce((sum, partition) => sum + Math.max(0, partition.size || 0) * partitionProgress(partition), 0) / totalBytes;
}

export function formatDateTime(timestamp: number): string {
  if (!Number.isFinite(timestamp) || timestamp <= 0) return '—';
  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) return '—';
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(date);
}

export function formatLogTime(timestamp: number): string {
  if (!Number.isFinite(timestamp) || timestamp <= 0) return '—';
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(new Date(timestamp));
}

export function formatDuration(start: number, end: number): string {
  if (!Number.isFinite(start) || !Number.isFinite(end) || start <= 0 || end < start) return '—';
  const seconds = Math.round((end - start) / 1000);
  if (seconds < 60) return `${seconds} 秒`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`;
  return `${Math.floor(seconds / 3600)} 时 ${Math.floor((seconds % 3600) / 60)} 分`;
}

export function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message || '操作失败，请查看运行日志。';
  if (typeof error === 'string' && error.trim()) return error;
  return '操作失败，请查看运行日志。';
}

export function validatePreferences(preferences: Preferences): string | null {
  if (!Number.isInteger(preferences.workers) || preferences.workers < 1 || preferences.workers > 16) return '并行线程必须是 1 到 16 之间的整数。';
  if (!['light', 'dark', 'system'].includes(preferences.theme)) return '请选择有效的外观主题。';
  if (!preferences.outputDir.trim()) return '请选择输出目录。';
  if (/[\r\n]/.test(preferences.userAgent)) return 'User-Agent 不能包含换行符。';
  return null;
}

export function samePreferences(left: Preferences, right: Preferences): boolean {
  return left.theme === right.theme && left.workers === right.workers && left.verify === right.verify
    && left.userAgent === right.userAgent && left.outputDir === right.outputDir;
}
