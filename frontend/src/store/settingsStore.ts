import { create } from 'zustand';
import { configApi } from '../api/configApi';

const STORAGE_KEY_API = 'lavis_api_key';
const STORAGE_KEY_URL = 'lavis_base_url';
const STORAGE_KEY_CHAT_MODEL = 'lavis_chat_model_name';
const STORAGE_KEY_STT_MODEL = 'lavis_stt_model_name';
const STORAGE_KEY_TTS_MODEL = 'lavis_tts_model_name';
const FIRST_LAUNCH_COMPLETED_KEY = 'lavis_first_launch_completed';

type ApiMode = 'official' | 'proxy';

interface SettingsState {
  apiKey: string | null;
  baseUrl: string | null;
  /** 运行时覆盖的主对话模型名称（对应 app.llm.models.fast-model.model-name） */
  chatModelName: string | null;
  /** 运行时覆盖的 STT 模型名称（对应 app.llm.models.whisper.model-name） */
  sttModelName: string | null;
  /** 运行时覆盖的 TTS 模型名称（对应 app.llm.models.tts.model-name） */
  ttsModelName: string | null;
  mode: ApiMode;
  isConfigured: boolean;
  isLoading: boolean;
  error: string | null;
}

interface SettingsActions {
  setConfig: (
    apiKey: string,
    baseUrl?: string,
    chatModelName?: string,
    sttModelName?: string,
    ttsModelName?: string,
  ) => Promise<void>;
  clearConfig: () => Promise<void>;
  loadFromStorage: () => void;
  checkStatus: () => Promise<void>;
  setError: (error: string | null) => void;
}

const initialState: SettingsState = {
  apiKey: null,
  baseUrl: null,
  chatModelName: null,
  sttModelName: null,
  ttsModelName: null,
  mode: 'official',
  isConfigured: false,
  isLoading: false,
  error: null,
};

export const useSettingsStore = create<SettingsState & SettingsActions>((set, get) => ({
  ...initialState,

  setConfig: async (
    apiKey: string,
    baseUrl?: string,
    chatModelName?: string,
    sttModelName?: string,
    ttsModelName?: string,
  ) => {
    set({ isLoading: true, error: null });
    try {
      // Save to backend
      const response = await configApi.setApiKey(apiKey, baseUrl, chatModelName, sttModelName, ttsModelName);
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

      if (chatModelName) {
        localStorage.setItem(STORAGE_KEY_CHAT_MODEL, chatModelName);
      } else {
        localStorage.removeItem(STORAGE_KEY_CHAT_MODEL);
      }

      if (sttModelName) {
        localStorage.setItem(STORAGE_KEY_STT_MODEL, sttModelName);
      } else {
        localStorage.removeItem(STORAGE_KEY_STT_MODEL);
      }

      if (ttsModelName) {
        localStorage.setItem(STORAGE_KEY_TTS_MODEL, ttsModelName);
      } else {
        localStorage.removeItem(STORAGE_KEY_TTS_MODEL);
      }

      const mode: ApiMode = baseUrl ? 'proxy' : 'official';
      set({
        apiKey,
        baseUrl: baseUrl || null,
        chatModelName: chatModelName || null,
        sttModelName: sttModelName || null,
        ttsModelName: ttsModelName || null,
        mode,
        isConfigured: true,
        isLoading: false,
      });
      
      // 标记首次启动已完成（用户已成功配置 API Key）
      localStorage.setItem(FIRST_LAUNCH_COMPLETED_KEY, 'true');
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
      localStorage.removeItem(STORAGE_KEY_CHAT_MODEL);
      localStorage.removeItem(STORAGE_KEY_STT_MODEL);
      localStorage.removeItem(STORAGE_KEY_TTS_MODEL);

      set({
        apiKey: null,
        baseUrl: null,
        chatModelName: null,
        sttModelName: null,
        ttsModelName: null,
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
    const storedChatModel = localStorage.getItem(STORAGE_KEY_CHAT_MODEL) || undefined;
    const storedSttModel = localStorage.getItem(STORAGE_KEY_STT_MODEL) || undefined;
    const storedTtsModel = localStorage.getItem(STORAGE_KEY_TTS_MODEL) || undefined;

    if (storedKey) {
      // Sync to backend
      get()
        .setConfig(storedKey, storedUrl || undefined, storedChatModel, storedSttModel, storedTtsModel)
        .catch((error) => {
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
