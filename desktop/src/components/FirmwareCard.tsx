import { Copy, Fingerprint, Info, ScanLine } from 'lucide-react';
import type { DesktopController } from '../hooks/useFluxDesktop';
import { metadataValue } from '../utils';
import { CardHeading, IconButton, StatusBadge } from './Common';

export function FirmwareCard({ desktop }: { desktop: DesktopController }) {
  const archive = desktop.state.archive;
  const fingerprint = metadataValue(archive?.fingerprint);
  const fields = [
    { label: 'Android 版本', value: metadataValue(archive?.androidVersion) },
    { label: '安全补丁', value: metadataValue(archive?.securityPatchLevel) },
    { label: '内核版本', value: metadataValue(archive?.kernelVersion) },
    { label: '块大小', value: archive && archive.blockSize > 0 ? `${archive.blockSize.toLocaleString('zh-CN')} B` : '—' },
  ];
  return <section className="card firmware-card" aria-label="固件信息">
    <CardHeading icon={ScanLine} title="固件信息" action={<StatusBadge tone={archive ? 'success' : 'neutral'} dot>{archive ? '已读取' : '待解析'}</StatusBadge>} />
    <dl className="metadata-grid">{fields.map((field) => <div key={field.label} className="metadata-field">
      <dt>{field.label}</dt><dd title={field.value} className={field.value === '—' ? 'is-unknown' : ''}>{field.value}</dd>
    </div>)}</dl>
    <div className="fingerprint-block">
      <div className="fingerprint-block__label"><span><Fingerprint size={14} aria-hidden="true" />构建指纹</span>
        <IconButton label="复制完整构建指纹" disabled={fingerprint === '—'} onClick={() => void desktop.copyText(archive!.fingerprint, '完整构建指纹')}><Copy size={14} /></IconButton>
      </div>
      <code title={fingerprint} className={fingerprint === '—' ? 'is-unknown' : ''}>{fingerprint}</code>
    </div>
    <p className="metadata-note"><Info size={13} aria-hidden="true" />缺失字段显示 —，不使用当前系统信息替代。</p>
  </section>;
}
