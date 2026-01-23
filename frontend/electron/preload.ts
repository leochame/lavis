import { contextBridge, ipcRenderer } from 'electron';

contextBridge.exposeInMainWorld('electron', {
  ipcRenderer: {
    sendMessage: (channel: string, data: unknown) => {
      ipcRenderer.send(channel, data);
    },
    on: (channel: string, callback: (...args: unknown[]) => void) => {
      ipcRenderer.on(channel, (_event, ...args) => callback(...args));
    },
    once: (channel: string, callback: (...args: unknown[]) => void) => {
      ipcRenderer.once(channel, (_event, ...args) => callback(...args));
    },
    removeAllListeners: (channel: string) => {
      ipcRenderer.removeAllListeners(channel);
    },
  },
  platform: {
    resizeWindow: (mode: 'capsule' | 'chat' | 'idle' | 'listening' | 'expanded') => ipcRenderer.invoke('platform:resize-window', { mode }),
    resizeWindowMini: () => ipcRenderer.invoke('resize-window-mini'),
    resizeWindowFull: () => ipcRenderer.invoke('resize-window-full'),
    minimizeWindow: () => ipcRenderer.invoke('platform:minimize'),
    hideWindow: () => ipcRenderer.invoke('platform:hide'),
    setAlwaysOnTop: (flag: boolean) => ipcRenderer.invoke('platform:set-always-on-top', { flag }),
    setIgnoreMouseEvents: (ignore: boolean, forward?: boolean) =>
      ipcRenderer.invoke('platform:set-ignore-mouse', { ignore, forward }),
    getSnapState: () => ipcRenderer.invoke('platform:get-snap-state'),
    getScreenshot: () => ipcRenderer.invoke('platform:get-screenshot'),
    openExternalUrl: (url: string) => ipcRenderer.invoke('platform:open-external', { url }),
    checkMicrophonePermission: () => ipcRenderer.invoke('platform:check-mic'),
    registerGlobalShortcut: (accelerator: string, action: 'toggle-window') =>
      ipcRenderer.invoke('platform:register-shortcut', { accelerator, action }),
  },
  backend: {
    request: (method: 'GET' | 'POST' | 'PUT' | 'DELETE', endpoint: string, data?: unknown, port?: number) =>
      ipcRenderer.invoke('backend:request', { method, endpoint, data, port }),
  },
});

// Type definitions for the exposed API
declare global {
  interface Window {
    electron: {
      ipcRenderer: {
        sendMessage: (channel: string, data: unknown) => void;
        on: (channel: string, callback: (...args: unknown[]) => void) => void;
        once: (channel: string, callback: (...args: unknown[]) => void) => void;
        removeAllListeners: (channel: string) => void;
      };
      platform: {
        resizeWindow: (mode: 'capsule' | 'chat' | 'idle' | 'listening' | 'expanded') => Promise<void>;
        resizeWindowMini: () => Promise<void>;
        resizeWindowFull: () => Promise<void>;
        minimizeWindow: () => Promise<void>;
        hideWindow: () => Promise<void>;
        setAlwaysOnTop: (flag: boolean) => Promise<void>;
        setIgnoreMouseEvents: (ignore: boolean, forward?: boolean) => Promise<void>;
        getSnapState: () => Promise<{ isSnapped: boolean; position: 'left' | 'right' | 'top' | 'bottom' | null }>;
        getScreenshot: () => Promise<string | null>;
        openExternalUrl: (url: string) => void;
        checkMicrophonePermission: () => Promise<boolean>;
        registerGlobalShortcut?: (accelerator: string, action: 'toggle-window') => Promise<boolean>;
      };
      backend: {
        request: (method: 'GET' | 'POST' | 'PUT' | 'DELETE', endpoint: string, data?: unknown, port?: number) => Promise<{ status: number; data: unknown }>;
      };
    };
  }
}
