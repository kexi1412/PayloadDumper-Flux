import { Component, StrictMode, type ErrorInfo, type ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import { installNativeBridge } from './nativeBridge';
import './styles.css';

installNativeBridge();

class AppErrorBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  state: { error: Error | null } = { error: null };
  static getDerivedStateFromError(error: Error) { return { error }; }
  componentDidCatch(error: Error, info: ErrorInfo) { console.error('Flux renderer error', error, info.componentStack); }
  render() {
    if (this.state.error) return <main className="fatal-error"><div className="card"><span className="eyebrow">PAYLOAD DUMPER · FLUX</span>
      <h1>界面暂时无法加载</h1><p>桌面界面遇到了错误。重新加载界面不会伪造或重启提取任务。</p><pre>{this.state.error.message}</pre>
      <button type="button" className="button button--primary" onClick={() => window.location.reload()}>重新加载界面</button>
    </div></main>;
    return this.props.children;
  }
}

const container = document.getElementById('root');
if (!container) throw new Error('Flux application root was not found.');
createRoot(container).render(<StrictMode><AppErrorBoundary><App /></AppErrorBoundary></StrictMode>);
