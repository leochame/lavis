import { createContext, useContext, useMemo, type ReactNode } from 'react';
import type { PlatformContextValue } from './interface';
import { WebPlatformService } from './web/platformService';
import { ElectronPlatformService } from './electron/platformService';

const defaultValue: PlatformContextValue = {
  platform: new WebPlatformService(),
  isElectron: false,
};

const PlatformContext = createContext<PlatformContextValue>(defaultValue);

export function PlatformProvider({ children }: { children: ReactNode }) {
  const value = useMemo<PlatformContextValue>(() => {
    const electron = window.electron;
    const isElectron = typeof window !== 'undefined' && !!electron?.platform;
    console.log('🔧 PlatformProvider: isElectron =', isElectron, 'window.electron =', !!electron);
    if (isElectron && electron?.platform) {
      return {
        platform: new ElectronPlatformService(electron.platform),
        isElectron,
      };
    }
    return defaultValue;
  }, []);

  return <PlatformContext.Provider value={value}>{children}</PlatformContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function usePlatform(): PlatformContextValue {
  return useContext(PlatformContext);
}

