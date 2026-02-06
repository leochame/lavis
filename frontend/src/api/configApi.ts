import axios from 'axios';

const API_BASE_URL = 'http://127.0.0.1:18765/api/config';

interface ApiKeyStatusResponse {
  configured: boolean;
}

interface SetApiKeyResponse {
  success: boolean;
  message?: string;
  error?: string;
}

interface ClearApiKeyResponse {
  success: boolean;
  message?: string;
  error?: string;
}

/**
 * Config API client for managing API keys
 */
export const configApi = {
  /**
   * Set the Gemini API key
   */
  async setApiKey(apiKey: string): Promise<SetApiKeyResponse> {
    const response = await axios.post<SetApiKeyResponse>(
      `${API_BASE_URL}/api-key`,
      { apiKey },
      { timeout: 5000 }
    );
    return response.data;
  },

  /**
   * Get API key configuration status
   */
  async getApiKeyStatus(): Promise<ApiKeyStatusResponse> {
    const response = await axios.get<ApiKeyStatusResponse>(
      `${API_BASE_URL}/api-key/status`,
      { timeout: 5000 }
    );
    return response.data;
  },

  /**
   * Clear the API key
   */
  async clearApiKey(): Promise<ClearApiKeyResponse> {
    const response = await axios.delete<ClearApiKeyResponse>(
      `${API_BASE_URL}/api-key`,
      { timeout: 5000 }
    );
    return response.data;
  },
};
