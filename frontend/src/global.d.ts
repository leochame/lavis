/// <reference types="vite/client" />

// Electron IPC 类型声明
interface ElectronAPI {
  ipcRenderer: {
    sendMessage: (channel: string, data?: unknown) => void;
    on: (channel: string, callback: (...args: unknown[]) => void) => void;
    removeListener: (channel: string, callback: (...args: unknown[]) => void) => void;
    removeAllListeners: (channel: string) => void;
  };
  platform?: {
    resizeWindow: (mode: 'capsule' | 'chat' | 'idle' | 'listening' | 'expanded') => Promise<void>;
    resizeWindowMini?: () => Promise<void>;
    resizeWindowFull?: () => Promise<void>;
    minimizeWindow: () => Promise<void>;
    hideWindow: () => Promise<void>;
    setAlwaysOnTop: (flag: boolean) => Promise<void>;
    setIgnoreMouseEvents: (ignore: boolean, forward?: boolean) => Promise<void>;
    getSnapState: () => Promise<{ isSnapped: boolean; position: 'left' | 'right' | 'top' | 'bottom' | null }>;
    getScreenshot: () => Promise<string | null>;
    openExternalUrl: (url: string) => void;
    checkMicrophonePermission: () => Promise<boolean>;
    registerGlobalShortcut?: (accelerator: string, action: 'toggle-window') => Promise<boolean>;
    dragStart?: (mouseX: number, mouseY: number) => Promise<void>;
    dragMove?: (mouseX: number, mouseY: number) => Promise<void>;
    dragEnd?: () => Promise<void>;
    getWindowPosition?: () => Promise<{ x: number; y: number }>;
    setWindowPosition?: (x: number, y: number, animate?: boolean) => Promise<void>;
  };
  backend?: {
    request: (method: 'GET' | 'POST' | 'PUT' | 'DELETE', endpoint: string, data?: unknown, port?: number) => Promise<{ status: number; data: unknown }>;
  };
}

// 扩展 Window 接口
declare global {
  interface Window {
    electron?: ElectronAPI;
  }
}

// Vite 静态资源导入类型
declare module '*.ppn?url' {
  const url: string;
  export default url;
}

declare module '*.pv?url' {
  const url: string;
  export default url;
}

export {};
