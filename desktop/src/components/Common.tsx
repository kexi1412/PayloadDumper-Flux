import { useEffect, useRef, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { AlertCircle, CheckCircle2, Info, Loader2, X, type LucideIcon } from 'lucide-react';
import type { EnginePhase } from '../types';
import type { ToastMessage } from '../hooks/useFluxDesktop';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
  loading?: boolean;
  small?: boolean;
}

export function Button({ variant = 'secondary', loading = false, small = false, className = '', children, disabled, ...props }: ButtonProps) {
  return <button type="button" {...props} className={`button button--${variant}${small ? ' button--small' : ''} ${className}`}
    disabled={disabled || loading} aria-busy={loading || undefined}>
    {loading && <Loader2 size={16} className="spin" aria-hidden="true" />}{children}
  </button>;
}

export function IconButton({ label, children, className = '', ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { label: string }) {
  return <button type="button" {...props} aria-label={label} title={props.title ?? label} className={`icon-button ${className}`}>{children}</button>;
}

export function CardHeading({ icon: Icon, title, description, action }: { icon: LucideIcon; title: string; description?: string; action?: ReactNode }) {
  return <div className="card-heading">
    <div className="card-heading__main"><span className="card-heading__icon"><Icon size={18} aria-hidden="true" /></span>
      <div><h2>{title}</h2>{description && <p>{description}</p>}</div>
    </div>{action}
  </div>;
}

export function EmptyState({ icon: Icon, title, children, compact = false, action }: { icon: LucideIcon; title: string; children: ReactNode; compact?: boolean; action?: ReactNode }) {
  return <div className={`empty-state${compact ? ' empty-state--compact' : ''}`}>
    <span className="empty-state__icon"><Icon size={26} strokeWidth={1.5} aria-hidden="true" /></span>
    <h3>{title}</h3><p>{children}</p>{action}
  </div>;
}

export function Checkbox({ indeterminate = false, ...props }: InputHTMLAttributes<HTMLInputElement> & { indeterminate?: boolean }) {
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => { if (ref.current) ref.current.indeterminate = indeterminate; }, [indeterminate]);
  return <input {...props} ref={ref} type="checkbox" className={`checkbox ${props.className ?? ''}`} />;
}

export const PHASE_LABELS: Record<EnginePhase, string> = {
  idle: '等待固件', parsing: '正在解析', ready: '解析就绪', extracting: '正在提取', cancelling: '正在取消', error: '引擎异常',
};

export function StatusBadge({ tone = 'neutral', children, dot = false }: { tone?: 'neutral' | 'success' | 'warning' | 'error' | 'accent'; children: ReactNode; dot?: boolean }) {
  return <span className={`badge badge--${tone}`}>{dot && <span className="status-dot" aria-hidden="true" />}{children}</span>;
}

export function ProgressBar({ value, label, tone = 'accent' }: { value: number; label: string; tone?: 'accent' | 'success' | 'error' }) {
  const safeValue = Number.isFinite(value) ? Math.max(0, Math.min(100, value)) : 0;
  return <div className={`progress progress--${tone}`} role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(safeValue)}>
    <div className="progress__fill" style={{ width: `${safeValue}%` }} />
  </div>;
}

export function ConfirmDialog({ open, title, children, confirmLabel, busy, onConfirm, onClose }: {
  open: boolean; title: string; children: ReactNode; confirmLabel: string; busy?: boolean; onConfirm: () => void; onClose: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current;
    if (open && dialog && !dialog.open) dialog.showModal();
    else if (!open && dialog?.open) dialog.close();
  }, [open]);
  return createPortal(<dialog className="confirm-dialog" ref={ref} aria-labelledby="confirm-title"
    onCancel={(event) => { event.preventDefault(); if (!busy) onClose(); }}>
    <div className="confirm-dialog__heading"><span className="dialog-warning"><AlertCircle size={22} aria-hidden="true" /></span>
      <IconButton label="关闭对话框" onClick={onClose} disabled={busy}><X size={18} /></IconButton>
    </div>
    <h2 id="confirm-title">{title}</h2><div className="confirm-dialog__body">{children}</div>
    <div className="confirm-dialog__actions"><Button onClick={onClose} disabled={busy}>取消</Button>
      <Button variant="danger" loading={busy} onClick={onConfirm}>{confirmLabel}</Button>
    </div>
  </dialog>, document.body);
}

export function ToastRegion({ toasts, onDismiss }: { toasts: ToastMessage[]; onDismiss: (id: number) => void }) {
  return <div className="toast-region" aria-label="操作通知">
    {toasts.map((toast) => {
      const Icon = toast.tone === 'error' ? AlertCircle : toast.tone === 'success' ? CheckCircle2 : Info;
      return <div key={toast.id} className={`toast toast--${toast.tone}`} role={toast.tone === 'error' ? 'alert' : 'status'}>
        <Icon size={19} aria-hidden="true" /><span>{toast.message}</span>
        <IconButton label="关闭通知" onClick={() => onDismiss(toast.id)}><X size={15} /></IconButton>
      </div>;
    })}
  </div>;
}
