import { create } from 'zustand';
import { configApi } from '../api/configApi';

const STORAGE_KEY = 'lavis_api_key';

interface SettingsState {
  apiKey: string | null;
  isConfigured: boolean;
  isLoading: boolean;
  error: string | null;
}

interface SettingsActions {
  setApiKey: (key: string) => Promise<void>;
  clearApiKey: () => Promise<void>;
  loadFromStorage: () => void;
  checkStatus: () => Promise<void>;
  setError: (error: string | null) => void;
}

const initialState: SettingsState = {
  apiKey: null,
  isConfigured: false,
  isLoading: false,
  error: null,
};

export const useSettingsStore = create<SettingsState & SettingsActions>((set, get) => ({
  ...initialState,

  setApiKey: async (key: string) => {
    set({ isLoading: true, error: null });
    try {
      // Save to backend
      const response = await configApi.setApiKey(key);
      if (!response.success) {
        throw new Error(response.error || 'Failed to set API key');
      }

      // Save to localStorage for persistence across page reloads
      localStorage.setItem(STORAGE_KEY, key);

      set({
        apiKey: key,
        isConfigured: true,
        isLoading: false,
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to set API key';
      set({ isLoading: false, error: message });
      throw error;
    }
  },

  clearApiKey: async () => {
    set({ isLoading: true, error: null });
    try {
      // Clear from backend
      await configApi.clearApiKey();

      // Clear from localStorage
      localStorage.removeItem(STORAGE_KEY);

      set({
        apiKey: null,
        isConfigured: false,
        isLoading: false,
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to clear API key';
      set({ isLoading: false, error: message });
      throw error;
    }
  },

  loadFromStorage: () => {
    const storedKey = localStorage.getItem(STORAGE_KEY);
    if (storedKey) {
      // Sync to backend
      get().setApiKey(storedKey).catch((error) => {
        console.error('Failed to sync API key to backend:', error);
      });
    }
  },

  checkStatus: async () => {
    try {
      const status = await configApi.getApiKeyStatus();
      set({ isConfigured: status.configured });
    } catch (error) {
      console.error('Failed to check API key status:', error);
    }
  },

  setError: (error: string | null) => set({ error }),
}));
