import { useEffect } from 'react';
import type { IPlatformService } from '../../platforms/interface';
import type { ViewMode } from '../../store/uiStore';

interface UseElectronWindowSyncParams {
  isElectron: boolean;
  platform: IPlatformService;
  viewMode: ViewMode;
}

/**
 * 同步 React 视图模式与 Electron 物理窗口模式。
 */
export function useElectronWindowSync({
  isElectron,
  platform,
  viewMode,
}: UseElectronWindowSyncParams) {
  useEffect(() => {
    if (!isElectron) {
      return;
    }

    const targetMode = viewMode === 'chat' ? 'expanded' : 'capsule';
    platform.resizeWindow(targetMode);
  }, [isElectron, platform, viewMode]);
}
