import { useCallback } from 'react';
import type { ViewMode, WindowState } from '../../store/uiStore';

interface UseAppInteractionsParams {
  isElectron: boolean;
  setViewMode: (mode: ViewMode) => void;
  setWindowState: (state: WindowState) => void;
  setIsStarted: (started: boolean) => void;
}

export function useAppInteractions({
  isElectron,
  setViewMode,
  setWindowState,
  setIsStarted,
}: UseAppInteractionsParams) {
  const handleCapsuleClick = useCallback(() => {
    // 单击现在用于开始录音，由 Capsule 组件内部处理
  }, []);

  const handleCapsuleDoubleClick = useCallback(() => {
    setViewMode('chat');
    setWindowState('expanded');
  }, [setViewMode, setWindowState]);

  const handleCapsuleContextMenu = useCallback(() => {
    const electron = window.electron;
    if (isElectron && electron?.ipcRenderer) {
      electron.ipcRenderer.sendMessage('show-context-menu', {});
    }
  }, [isElectron]);

  const handleChatClose = useCallback(() => {
    setViewMode('capsule');
    setWindowState('idle');
    if (isElectron && window.electron?.platform) {
      setTimeout(() => window.electron?.platform?.resizeWindow('capsule'), 50);
    }
  }, [setViewMode, setWindowState, isElectron]);

  const handleMicStart = useCallback(() => {
    setIsStarted(true);
    setTimeout(() => {
      window.dispatchEvent(new CustomEvent('lavis-auto-record'));
    }, 100);
  }, [setIsStarted]);

  return {
    handleCapsuleClick,
    handleCapsuleDoubleClick,
    handleCapsuleContextMenu,
    handleChatClose,
    handleMicStart,
  };
}
