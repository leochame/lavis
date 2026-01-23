import { create } from 'zustand';

export type ViewMode = 'capsule' | 'chat';

/**
 * 窗口状态枚举
 * - Idle: 休眠/待机，胶囊隐藏或仅显示托盘图标
 * - Listening: 语音唤醒/监听中，显示胶囊，呼吸灯动效，不显示面板
 * - Expanded: 交互展开，显示完整聊天与工作流面板
 */
export type WindowState = 'idle' | 'listening' | 'expanded';

interface UIState {
  viewMode: ViewMode;
  windowState: WindowState;
  theme: 'light' | 'dark';
  isDraggable: boolean;
  isTtsPlaying: boolean; // TTS 播放状态，用于显示声波纹路
}

interface UIActions {
  setViewMode: (viewMode: ViewMode) => void;
  toggleViewMode: () => void;
  setWindowState: (state: WindowState) => void;
  setTtsPlaying: (playing: boolean) => void;
  setTheme: (theme: 'light' | 'dark') => void;
  setDraggable: (flag: boolean) => void;
}

export const useUIStore = create<UIState & UIActions>((set) => ({
  viewMode: 'capsule',
  windowState: 'idle',
  theme: 'dark',
  isDraggable: false,
  isTtsPlaying: false,
  setViewMode: (viewMode) => set({ viewMode }),
  toggleViewMode: () =>
    set((state) => ({
      viewMode: state.viewMode === 'capsule' ? 'chat' : 'capsule',
    })),
  setWindowState: (windowState) => set({ windowState }),
  setTtsPlaying: (isTtsPlaying) => set({ isTtsPlaying }),
  setTheme: (theme) => set({ theme }),
  setDraggable: (flag) => set({ isDraggable: flag }),
}));

