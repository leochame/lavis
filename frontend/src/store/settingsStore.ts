import { create } from 'zustand';
import { configApi } from '../api/configApi';

type ApiMode = 'official' | 'proxy';

interface SettingsState {
  mode: ApiMode;
  baseUrl: string | null;
  isConfigured: boolean;
  isLoading: boolean;
  error: string | null;
  source: 'env' | null;
  chatConfigured: boolean;
  sttConfigured: boolean;
  ttsConfigured: boolean;
}

interface SettingsActions {
  checkStatus: () => Promise<void>;
  setError: (error: string | null) => void;
}

const initialState: SettingsState = {
  mode: 'official',
  baseUrl: null,
  isConfigured: false,
  isLoading: false,
  error: null,
  source: null,
  chatConfigured: false,
  sttConfigured: false,
  ttsConfigured: false,
};

export const useSettingsStore = create<SettingsState & SettingsActions>((set) => ({
  ...initialState,

  checkStatus: async () => {
    set({ isLoading: true, error: null });
    try {
      const status = await configApi.getApiKeyStatus();
      set({
        isConfigured: status.configured,
        mode: status.mode,
        baseUrl: status.baseUrl,
        source: status.source ?? 'env',
        chatConfigured: status.chatConfigured ?? status.configured,
        sttConfigured: status.sttConfigured ?? status.configured,
        ttsConfigured: status.ttsConfigured ?? status.configured,
        isLoading: false,
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to check backend config status';
      set({ isLoading: false, error: message });
      console.error('Failed to check API config status:', error);
    }
  },

  setError: (error: string | null) => set({ error }),
}));
