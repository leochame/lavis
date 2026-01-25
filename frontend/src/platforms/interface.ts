export type WindowMode = 'capsule' | 'chat' | 'idle' | 'listening' | 'expanded';

export type SnapPosition = 'left' | 'right' | 'top' | 'bottom' | null;

export interface SnapState {
  isSnapped: boolean;
  position: SnapPosition;
}

export interface IPlatformService {
  // 窗口控制
  resizeWindow: (mode: WindowMode) => Promise<void> | void;
  minimizeWindow: () => Promise<void> | void;
  hideWindow: () => Promise<void> | void;
  setAlwaysOnTop: (flag: boolean) => Promise<void> | void;
  setIgnoreMouseEvents: (ignore: boolean, forward?: boolean) => Promise<void> | void;
  getSnapState: () => Promise<SnapState>;

  // 系统能力
  getScreenshot: () => Promise<string | null>;
  openExternalUrl: (url: string) => void;

  // 硬件/权限
  checkMicrophonePermission: () => Promise<boolean>;

  // 预留扩展能力
  registerGlobalShortcut?: (accelerator: string, action: 'toggle-window') => Promise<boolean>;
}

export interface PlatformContextValue {
  platform: IPlatformService;
  isElectron: boolean;
}

