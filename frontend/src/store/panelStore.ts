import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { AgentPanelType } from '../types/panel';

const COMPACT_BREAKPOINT = 980;

function getInitialCompact(): boolean {
  if (typeof window === 'undefined') return false;
  return window.innerWidth <= COMPACT_BREAKPOINT;
}

interface PanelState {
  activePanel: AgentPanelType;
  isCompact: boolean;
}

interface PanelActions {
  setActivePanel: (panel: AgentPanelType) => void;
  openSettingsPanel: () => void;
  setCompactByViewport: (viewportWidth: number) => void;
}

export const usePanelStore = create<PanelState & PanelActions>()(
  persist(
    (set) => ({
      activePanel: 'chat',
      isCompact: getInitialCompact(),
      setActivePanel: (activePanel) => set({ activePanel }),
      openSettingsPanel: () => set({ activePanel: 'settings' }),
      setCompactByViewport: (viewportWidth) => set({ isCompact: viewportWidth <= COMPACT_BREAKPOINT }),
    }),
    {
      name: 'lavis_panel_state_v1',
      partialize: (state) => ({ activePanel: state.activePanel }),
    },
  ),
);
