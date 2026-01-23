import type { IPlatformService, WindowMode, SnapState } from '../interface';

type ElectronPlatformBridge = {
  resizeWindow: (mode: WindowMode) => Promise<void>;
  minimizeWindow: () => Promise<void>;
  hideWindow: () => Promise<void>;
  setAlwaysOnTop: (flag: boolean) => Promise<void>;
  setIgnoreMouseEvents: (ignore: boolean, forward?: boolean) => Promise<void>;
  getSnapState: () => Promise<SnapState>;
  getScreenshot: () => Promise<string | null>;
  openExternalUrl: (url: string) => void;
  checkMicrophonePermission: () => Promise<boolean>;
  registerGlobalShortcut?: (accelerator: string, action: 'toggle-window') => Promise<boolean>;
};

/**
 * Electron 平台实现：调用 preload 暴露的受限桥接 API。
 */
export class ElectronPlatformService implements IPlatformService {
  private bridge: ElectronPlatformBridge;

  constructor(bridge: ElectronPlatformBridge) {
    this.bridge = bridge;
  }

  resizeWindow(mode: WindowMode): Promise<void> {
    return this.bridge.resizeWindow(mode);
  }

  minimizeWindow(): Promise<void> {
    return this.bridge.minimizeWindow();
  }

  hideWindow(): Promise<void> {
    return this.bridge.hideWindow();
  }

  setAlwaysOnTop(flag: boolean): Promise<void> {
    return this.bridge.setAlwaysOnTop(flag);
  }

  setIgnoreMouseEvents(ignore: boolean, forward?: boolean): Promise<void> {
    return this.bridge.setIgnoreMouseEvents(ignore, forward);
  }

  getSnapState(): Promise<SnapState> {
    return this.bridge.getSnapState();
  }

  getScreenshot(): Promise<string | null> {
    return this.bridge.getScreenshot();
  }

  openExternalUrl(url: string): void {
    this.bridge.openExternalUrl(url);
  }

  checkMicrophonePermission(): Promise<boolean> {
    return this.bridge.checkMicrophonePermission();
  }

  registerGlobalShortcut(accelerator: string, action: 'toggle-window'): Promise<boolean> {
    if (!this.bridge.registerGlobalShortcut) return Promise.resolve(false);
    return this.bridge.registerGlobalShortcut(accelerator, action);
  }
}

