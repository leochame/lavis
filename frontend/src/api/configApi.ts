import axios from 'axios';

const API_BASE_URL = 'http://127.0.0.1:18765/api/config';

interface ApiKeyStatusResponse {
  configured: boolean;
  mode: 'official' | 'proxy';
  baseUrl: string | null;
  source?: 'env';
  readOnly?: boolean;
  chatConfigured?: boolean;
  sttConfigured?: boolean;
  ttsConfigured?: boolean;
}

/**
 * Config API client for backend read-only configuration status.
 */
export const configApi = {
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
};
