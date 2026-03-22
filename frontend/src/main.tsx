import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import './tailwind.css';
import App from './App.tsx';
import { PlatformProvider } from './platforms/PlatformProvider';

// 暴露 STT 调试工具到全局（仅在 DEV 模式下）
if (import.meta.env.DEV) {
  import('./utils/sttDebug').then((m) => {
    if (typeof window !== 'undefined' && m.default) {
      (window as any).sttDebug = m.default;
      console.log('%c[STT Debug] 调试工具已加载', 'color: blue; font-weight: bold');
      console.log('使用方法:');
      console.log('  window.sttDebug.enable()      - 启用调试模式');
      console.log('  window.sttDebug.testEndpoint() - 测试 STT 端点');
      console.log('  window.sttDebug.checkConfig()  - 检查配置');
      console.log('  window.sttDebug.getLogs()      - 查看日志');
    }
  });
}

const root = createRoot(document.getElementById('root')!);
root.render(
  <StrictMode>
    <PlatformProvider>
      <App />
    </PlatformProvider>
  </StrictMode>,
);
