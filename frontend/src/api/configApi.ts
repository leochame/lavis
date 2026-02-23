import axios from 'axios';

const API_BASE_URL = 'http://127.0.0.1:18765/api/config';

interface ApiKeyStatusResponse {
  configured: boolean;
  mode: 'official' | 'proxy';
  baseUrl: string | null;
}

interface SetApiKeyResponse {
  success: boolean;
  message?: string;
  error?: string;
  mode?: 'official' | 'proxy';
}

interface ClearApiKeyResponse {
  success: boolean;
  message?: string;
  error?: string;
}

/**
 * Config API client for managing API keys and base URL
 *
 * Supports two modes:
 * 1. Official - Use Gemini official API (no baseUrl)
 * 2. Proxy - Use custom proxy/relay server (with baseUrl)
 */
export const configApi = {
  /**
   * Set the Gemini API key and optional base URL
   *
   * @param apiKey - Required API key
   * @param baseUrl - Optional base URL for proxy mode (empty = Gemini official)
   * @param chatModelName - Optional chat model-name override (e.g. gemini-2.0-flash)
   * @param sttModelName - Optional STT model-name override (e.g. gemini-1.5-flash)
   * @param ttsModelName - Optional TTS model-name override (e.g. gemini-2.0-flash)
   */
  async setApiKey(
    apiKey: string,
    baseUrl?: string,
    chatModelName?: string,
    sttModelName?: string,
    /**
     * When undefined, backend TTS config should be left unchanged.
     * When null/empty string, backend TTS config should be cleared.
     */
    ttsModelName?: string | null,
  ): Promise<SetApiKeyResponse> {
    const payload: Record<string, unknown> = {
      apiKey,
      baseUrl: baseUrl || null,
      chatModel: chatModelName || null,
      sttModel: sttModelName || null,
    };

    // IMPORTANT: only send ttsModel when explicitly provided.
    // Otherwise we would overwrite any existing backend TTS config with null.
    if (ttsModelName !== undefined) {
      payload.ttsModel = ttsModelName || null;
    }

    const response = await axios.post<SetApiKeyResponse>(
      `${API_BASE_URL}/api-key`,
      payload,
      { timeout: 0 }
    );
    return response.data;
  },

  /**
   * Get API key configuration status
   */
  async getApiKeyStatus(): Promise<ApiKeyStatusResponse> {
    const response = await axios.get<ApiKeyStatusResponse>(
      `${API_BASE_URL}/api-key/status`,
      { timeout: 0 }
    );
    return response.data;
  },

  /**
   * Clear the API key and base URL configuration
   */
  async clearApiKey(): Promise<ClearApiKeyResponse> {
    const response = await axios.delete<ClearApiKeyResponse>(
      `${API_BASE_URL}/api-key`,
      { timeout: 0 }
    );
    return response.data;
  },
};
