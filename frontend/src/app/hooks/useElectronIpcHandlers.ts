import { useEffect } from 'react';
import type { ViewMode, WindowState } from '../../store/uiStore';

interface UseElectronIpcHandlersParams {
  isElectron: boolean;
  handleChatClose: () => void;
  setViewMode: (mode: ViewMode) => void;
  setWindowState: (state: WindowState) => void;
}

/**
 * 绑定来自 Electron 主进程的 IPC 事件。
 */
export function useElectronIpcHandlers({
  isElectron,
  handleChatClose,
  setViewMode,
  setWindowState,
}: UseElectronIpcHandlersParams) {
  useEffect(() => {
    const electron = window.electron;
    if (!isElectron || !electron?.ipcRenderer) {
      return;
    }

    const handleSwitchToCapsule = () => {
      handleChatClose();
    };

    electron.ipcRenderer.on('switch-to-capsule', handleSwitchToCapsule);
    return () => {
      electron.ipcRenderer.removeListener('switch-to-capsule', handleSwitchToCapsule);
    };
  }, [isElectron, handleChatClose]);

  useEffect(() => {
    const electron = window.electron;
    if (!isElectron || !electron?.ipcRenderer) {
      return;
    }

    const handleSwitchToChat = () => {
      setViewMode('chat');
      setWindowState('expanded');
    };

    electron.ipcRenderer.on('switch-to-chat', handleSwitchToChat);
    return () => {
      electron.ipcRenderer.removeListener('switch-to-chat', handleSwitchToChat);
    };
  }, [isElectron, setViewMode, setWindowState]);

  useEffect(() => {
    const electron = window.electron;
    if (!isElectron || !electron?.ipcRenderer) {
      return;
    }

    const handleOpenSettings = () => {
      setViewMode('chat');
      setWindowState('expanded');
      window.dispatchEvent(new CustomEvent('lavis-open-settings'));
    };

    electron.ipcRenderer.on('open-settings', handleOpenSettings);
    return () => {
      electron.ipcRenderer.removeListener('open-settings', handleOpenSettings);
    };
  }, [isElectron, setViewMode, setWindowState]);
}
