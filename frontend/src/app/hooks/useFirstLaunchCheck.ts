import { useEffect, useState } from 'react';
import type { ViewMode, WindowState } from '../../store/uiStore';
import { useSettingsStore } from '../../store/settingsStore';

interface UseFirstLaunchCheckParams {
  isElectron: boolean;
  checkStatus: () => Promise<void>;
  setViewMode: (mode: ViewMode) => void;
  setWindowState: (state: WindowState) => void;
}

/**
 * Electron 首次启动检查：若首次且未配置 API Key，自动展开并打开设置面板。
 */
export function useFirstLaunchCheck({
  isElectron,
  checkStatus,
  setViewMode,
  setWindowState,
}: UseFirstLaunchCheckParams) {
  const [hasCheckedFirstLaunch, setHasCheckedFirstLaunch] = useState(false);

  useEffect(() => {
    if (!isElectron || hasCheckedFirstLaunch) {
      return;
    }

    let cancelled = false;
    const firstLaunchKey = 'lavis_first_launch_completed';
    const hasCompletedFirstLaunch = localStorage.getItem(firstLaunchKey) === 'true';

    const checkFirstLaunch = async () => {
      try {
        await new Promise((resolve) => setTimeout(resolve, 800));
        await checkStatus();
        await new Promise((resolve) => setTimeout(resolve, 300));

        const currentIsConfigured = useSettingsStore.getState().isConfigured;

        if (currentIsConfigured) {
          localStorage.setItem(firstLaunchKey, 'true');
        }

        if (!hasCompletedFirstLaunch && !currentIsConfigured) {
          setViewMode('chat');
          setWindowState('expanded');
          setTimeout(() => {
            window.dispatchEvent(new CustomEvent('lavis-open-settings'));
          }, 500);
        }
      } catch (error) {
        console.error('First launch check failed:', error);
      } finally {
        if (!cancelled) {
          setHasCheckedFirstLaunch(true);
        }
      }
    };

    checkFirstLaunch();

    return () => {
      cancelled = true;
    };
  }, [isElectron, hasCheckedFirstLaunch, checkStatus, setViewMode, setWindowState]);
}
