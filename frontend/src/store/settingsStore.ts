import { create } from 'zustand';
import { configApi } from '../api/configApi';

const STORAGE_KEY_API = 'lavis_api_key';
const STORAGE_KEY_URL = 'lavis_base_url';

type ApiMode = 'official' | 'proxy';

interface SettingsState {
  apiKey: string | null;
  baseUrl: string | null;
  mode: ApiMode;
  isConfigured: boolean;
  isLoading: boolean;
  error: string | null;
}

interface SettingsActions {
  setConfig: (apiKey: string, baseUrl?: string) => Promise<void>;
  clearConfig: () => Promise<void>;
  loadFromStorage: () => void;
  checkStatus: () => Promise<void>;
  setError: (error: string | null) => void;
}

const initialState: SettingsState = {
  apiKey: null,
  baseUrl: null,
  mode: 'official',
  isConfigured: false,
  isLoading: false,
  error: null,
};

export const useSettingsStore = create<SettingsState & SettingsActions>((set, get) => ({
  ...initialState,

  setConfig: async (apiKey: string, baseUrl?: string) => {
    set({ isLoading: true, error: null });
    try {
      // Save to backend
      const response = await configApi.setApiKey(apiKey, baseUrl);
      if (!response.success) {
        throw new Error(response.error || 'Failed to set API config');
      }

      // Save to localStorage for persistence across page reloads
      localStorage.setItem(STORAGE_KEY_API, apiKey);
      if (baseUrl) {
        localStorage.setItem(STORAGE_KEY_URL, baseUrl);
      } else {
        localStorage.removeItem(STORAGE_KEY_URL);
      }

      const mode: ApiMode = baseUrl ? 'proxy' : 'official';
      set({
        apiKey,
        baseUrl: baseUrl || null,
        mode,
        isConfigured: true,
        isLoading: false,
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to set API config';
      set({ isLoading: false, error: message });
      throw error;
    }
  },

  clearConfig: async () => {
    set({ isLoading: true, error: null });
    try {
      // Clear from backend
      await configApi.clearApiKey();

      // Clear from localStorage
      localStorage.removeItem(STORAGE_KEY_API);
      localStorage.removeItem(STORAGE_KEY_URL);

      set({
        apiKey: null,
        baseUrl: null,
        mode: 'official',
        isConfigured: false,
        isLoading: false,
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to clear API config';
      set({ isLoading: false, error: message });
      throw error;
    }
  },

  loadFromStorage: () => {
    const storedKey = localStorage.getItem(STORAGE_KEY_API);
    const storedUrl = localStorage.getItem(STORAGE_KEY_URL);

    if (storedKey) {
      // Sync to backend
      get().setConfig(storedKey, storedUrl || undefined).catch((error) => {
        console.error('Failed to sync API config to backend:', error);
      });
    }
  },

  checkStatus: async () => {
    try {
      const status = await configApi.getApiKeyStatus();
      set({
        isConfigured: status.configured,
        mode: status.mode,
        baseUrl: status.baseUrl,
      });
    } catch (error) {
      console.error('Failed to check API config status:', error);
    }
  },

  setError: (error: string | null) => set({ error }),
}));
