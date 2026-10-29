import { useState } from 'react';
import { ArrowRight, FileArchive, FileInput, FolderOpen, Globe2, HardDrive, Info, Link2, Square, UploadCloud } from 'lucide-react';
import type { LocalFile } from '../types';
import type { DesktopController } from '../hooks/useFluxDesktop';
import { formatBytes, isEngineBusy, validateSourceUrl } from '../utils';
import { Button, CardHeading, StatusBadge } from './Common';

export type SourceMode = 'file' | 'url';
export interface SourceSelection {
  mode: SourceMode;
  file: LocalFile | null;
  url: string;
}
interface SourceCardProps {
  desktop: DesktopController;
  source: SourceSelection;
  onModeChange: (mode: SourceMode) => void;
  onUrlChange: (url: string) => void;
  onPickFile: () => void;
  onDropFile: (file: File) => void;
  onParse: () => void;
}

export function SourceCard({ desktop, source, onModeChange, onUrlChange, onPickFile, onDropFile, onParse }: SourceCardProps) {
  const [dragging, setDragging] = useState(false);
  const [urlTouched, setUrlTouched] = useState(false);
  const locked = !desktop.bridge || desktop.busy || desktop.dialogBusy || desktop.initialLoading;
  const sourcePresent = source.mode === 'file' ? Boolean(source.file) : Boolean(source.url.trim());
  const urlError = source.mode === 'url' && urlTouched ? validateSourceUrl(source.url) : null;
  const parsing = desktop.state.phase === 'parsing';
  const cancelling = desktop.cancelPending || desktop.state.phase === 'cancelling';
  const canCancel = isEngineBusy(desktop.state.phase) && !cancelling;

  return <section className="card source-card" aria-label="固件来源">
    <CardHeading icon={FileInput} title="固件来源" action={<StatusBadge><span className="tiny-dot" />只读解析</StatusBadge>} />
    <div className="segmented-control" role="tablist" aria-label="选择固件来源方式">
      <button id="source-file-tab" role="tab" aria-selected={source.mode === 'file'} aria-controls="source-panel" tabIndex={source.mode === 'file' ? 0 : -1}
        className={source.mode === 'file' ? 'is-active' : ''} disabled={locked} onClick={() => onModeChange('file')}
        onKeyDown={(event) => { if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') { event.preventDefault(); onModeChange('url'); document.getElementById('source-url-tab')?.focus(); } }}>
        <HardDrive size={16} aria-hidden="true" />本地文件
      </button>
      <button id="source-url-tab" role="tab" aria-selected={source.mode === 'url'} aria-controls="source-panel" tabIndex={source.mode === 'url' ? 0 : -1}
        className={source.mode === 'url' ? 'is-active' : ''} disabled={locked} onClick={() => onModeChange('url')}
        onKeyDown={(event) => { if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') { event.preventDefault(); onModeChange('file'); document.getElementById('source-file-tab')?.focus(); } }}>
        <Globe2 size={16} aria-hidden="true" />在线链接
      </button>
    </div>
    <div id="source-panel" className="source-panel" role="tabpanel" aria-labelledby={source.mode === 'file' ? 'source-file-tab' : 'source-url-tab'}>
      {source.mode === 'file' ? <button type="button" className={`drop-zone${dragging ? ' is-dragging' : ''}${source.file ? ' has-file' : ''}`} disabled={locked}
        onClick={onPickFile} aria-label={source.file ? `已选择 ${source.file.name}，点击更换固件` : '选择本地固件文件，支持拖放'}
        onDragOver={(event) => { event.preventDefault(); event.dataTransfer.dropEffect = locked ? 'none' : 'copy'; if (!locked) setDragging(true); }}
        onDragLeave={(event) => { if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setDragging(false); }}
        onDrop={(event) => {
          event.preventDefault(); setDragging(false);
          if (locked) return;
          if (event.dataTransfer.files.length !== 1) { desktop.notify('请一次拖入一个固件文件。', 'error'); return; }
          const file = event.dataTransfer.files.item(0);
          if (file) onDropFile(file);
        }}>
        <span className="drop-zone__icon">{source.file ? <FileArchive size={25} strokeWidth={1.6} /> : <UploadCloud size={26} strokeWidth={1.6} />}</span>
        {source.file ? <><strong className="drop-zone__filename" title={source.file.name}>{source.file.name}</strong>
          <span className="drop-zone__path" title={source.file.path}>{source.file.path}</span>
          <span className="drop-zone__file-meta">{formatBytes(source.file.size)}<span>·</span><span className="text-accent">点击更换文件</span></span>
        </> : <><strong>拖放固件到这里，或<span className="text-accent">选择文件</span></strong>
          <span>从本地固件包中读取分区，无需上传文件</span>
          <span className="format-tags"><span>payload.bin</span><span>OTA ZIP</span><span>固件包</span></span>
        </>}
      </button> : <div className="url-source">
        <label htmlFor="firmware-url">固件下载直链</label>
        <div className={`input-with-icon${urlError ? ' has-error' : ''}`}><Link2 size={17} aria-hidden="true" />
          <input id="firmware-url" type="url" inputMode="url" autoComplete="off" spellCheck={false} placeholder="https://example.com/firmware.zip"
            value={source.url} disabled={locked} aria-invalid={Boolean(urlError)} aria-describedby="url-help"
            onChange={(event) => onUrlChange(event.target.value)} onBlur={() => setUrlTouched(true)} />
        </div>
        <p id="url-help" className={urlError ? 'field-error' : 'field-hint'}>{urlError || '使用支持 HTTP Range 的直链；提取引擎按需读取远程固件。'}</p>
        <div className="url-note"><Info size={15} aria-hidden="true" /><span>登录页、网盘分享页不是固件直链。User-Agent 可在设置中调整。</span></div>
      </div>}
    </div>
    <div className="source-card__footer">
      <span className="source-hint">{desktop.busy ? <><span className="status-dot is-pulsing" />来源已锁定，等待任务结束</> : <><FolderOpen size={14} aria-hidden="true" />选择后解析，不会自动提取</>}</span>
      <div className="inline-actions">
        {desktop.busy && <Button small onClick={() => void desktop.cancel()} disabled={!canCancel} loading={cancelling}>
          {!cancelling && <Square size={13} aria-hidden="true" />}{cancelling ? '取消中' : '取消任务'}
        </Button>}
        <Button variant="primary" onClick={() => { setUrlTouched(true); onParse(); }}
          loading={parsing || desktop.pendingAction === 'parse'} disabled={!desktop.available || desktop.busy || !sourcePresent || desktop.dialogBusy || desktop.savingPreferences || desktop.sourceResetting}
          title={!desktop.available ? '桌面引擎就绪后可解析固件' : '解析固件 (Ctrl+Enter)'}>
          {parsing ? '正在解析' : desktop.pendingAction === 'parse' ? '正在提交' : <>解析固件 <ArrowRight size={16} aria-hidden="true" /></>}
        </Button>
      </div>
    </div>
  </section>;
}
