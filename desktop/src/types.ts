export type EnginePhase = 'idle' | 'parsing' | 'ready' | 'extracting' | 'cancelling' | 'error';
export type ExtractState = 'IDLE' | 'RUNNING' | 'PAUSED' | 'DONE' | 'ERROR';
export type VerifyState = 'NONE' | 'VERIFYING' | 'PASSED' | 'FAILED' | 'SKIPPED';
export interface ArchiveInfo {
  fileName: string;
  fileSize: number;
  securityPatchLevel: string;
  partitionCount: number;
  blockSize: number;
  androidVersion: string;
  kernelVersion: string;
  fingerprint: string;
}
export interface Partition {
  partitionName: string;
  displayTitle: string;
  subtitle: string;
  size: number;
  compressedSize: number;
  sha256: string;
  extractState: ExtractState;
  verifyState: VerifyState;
  /** Completed fraction, from 0 to 1 inclusive. */
  progress: number;
  message: string;
}
export interface EngineState {
  phase: EnginePhase;
  detail: string;
  archive: ArchiveInfo | null;
  partitions: Partition[];
  outputDir: string | null;
  sourceLabel: string | null;
}
export interface EngineOptions {
  workers: number;
  verify: boolean;
  userAgent: string;
}
export interface ParseInput {
  source: { kind: 'file' | 'url'; value: string };
  outputDir: string;
  options: EngineOptions;
}
export interface ExtractInput {
  partitions: string[];
  outputDir: string;
  options: EngineOptions;
}
export interface LogEntry {
  time: number;
  level: 'info' | 'warn' | 'error';
  message: string;
}
export interface Preferences extends EngineOptions {
  theme: 'light' | 'dark' | 'system';
  outputDir: string;
}
export interface LocalFile { path: string; name: string; size: number }
export interface AppInfo {
  version: string;
  platform: string;
  defaultOutputDir: string;
  engineReady: boolean;
}
export interface HistoryEntry {
  id: string;
  archiveName: string;
  outputDir: string;
  startedAt: number;
  finishedAt: number;
  totalBytes: number;
  completed: number;
  failed: number;
  status: 'completed' | 'cancelled' | 'failed';
  partitions: string[];
}
export interface DesktopBridge {
  getAppInfo(): Promise<AppInfo>;
  getState(): Promise<EngineState>;
  pickFile(): Promise<LocalFile | null>;
  pathForFile(file: File): string;
  pickOutputDir(): Promise<string | null>;
  parse(input: ParseInput): Promise<void>;
  extract(input: ExtractInput): Promise<void>;
  cancel(): Promise<void>;
  reset(): Promise<void>;
  openPath(path: string): Promise<void>;
  copyText(text: string): Promise<void>;
  loadPreferences(): Promise<Preferences>;
  savePreferences(prefs: Preferences): Promise<Preferences>;
  getHistory(): Promise<HistoryEntry[]>;
  clearHistory(): Promise<void>;
  onState(callback: (state: EngineState) => void): () => void;
  onLog(callback: (entry: LogEntry) => void): () => void;
  onHistory(callback: (entries: HistoryEntry[]) => void): () => void;
}
declare global { interface Window { flux?: DesktopBridge } }
