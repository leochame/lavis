import axios, { type AxiosInstance } from 'axios';
import type {
  AgentStatus,
  ChatRequest,
  ChatResponse,
  TaskRequest,
  TaskResponse,
  TaskRecord,
  ScreenshotResponse,
} from '../types/agent';

class AgentApi {
  private client: AxiosInstance;
  private heartbeatInterval: number | null = null;
  private heartbeatCallback?: (status: AgentStatus | null) => void;
  private backendPort: number = 8080;
  private lastStatus: AgentStatus | null = null;
  private consecutiveErrors: number = 0;

  constructor(port?: number) {
    this.backendPort = port ?? 8080;

    this.client = axios.create({
      baseURL: `http://localhost:${this.backendPort}/api/agent`,
      timeout: 30000,
      headers: {
        'Content-Type': 'application/json',
      },
    });

    // Request interceptor for logging
    this.client.interceptors.request.use((config) => {
      console.log(`[API] ${config.method?.toUpperCase()} ${config.url}`, config.data);
      return config;
    });

    // Response interceptor for error handling
    this.client.interceptors.response.use(
      (response) => response,
      (error) => {
        console.error('[API] Error:', error.message);
        this.consecutiveErrors++;
        if (this.consecutiveErrors >= 5) {
          // After 5 consecutive errors, reduce polling frequency
          this.stopHeartbeat();
          this.startHeartbeat(this.heartbeatCallback!, 5000); // Poll every 5s instead of 1s
        }
        return Promise.reject(error);
      }
    );
  }

  // Heartbeat mechanism with adaptive polling
  startHeartbeat(callback: (status: AgentStatus | null) => void, intervalMs: number = 2000): void {
    this.heartbeatCallback = callback;
    this.checkStatus(); // Initial check

    // Adaptive polling: start fast, slow down if no changes
    this.heartbeatInterval = window.setInterval(async () => {
      await this.checkStatus();
    }, intervalMs);
  }

  stopHeartbeat(): void {
    if (this.heartbeatInterval) {
      clearInterval(this.heartbeatInterval);
      this.heartbeatInterval = null;
    }
  }

  private async checkStatus(): Promise<void> {
    try {
      const response = await this.getStatus();
      const newStatus = response.data;

      // Only callback if status actually changed
      if (JSON.stringify(newStatus) !== JSON.stringify(this.lastStatus)) {
        this.heartbeatCallback?.(newStatus);
        this.lastStatus = newStatus;
        this.consecutiveErrors = 0; // Reset error counter on success
      }
    } catch {
      this.heartbeatCallback?.(null);
      this.consecutiveErrors++;
    }
  }

  // Core APIs
  async chat(request: ChatRequest): Promise<ChatResponse> {
    const response = await this.client.post<ChatResponse>('/chat', request);
    return response.data;
  }

  async executeTask(request: TaskRequest): Promise<TaskResponse> {
    const response = await this.client.post<TaskResponse>('/task', request);
    return response.data;
  }

  // System Control
  async stop(): Promise<{ status: string }> {
    const response = await this.client.post<{ status: string }>('/stop');
    return response.data;
  }

  async reset(): Promise<{ status: string }> {
    const response = await this.client.post<{ status: string }>('/reset');
    return response.data;
  }

  async getStatus(): Promise<{ data: AgentStatus }> {
    return this.client.get<AgentStatus>('/status');
  }

  // Utilities
  async getScreenshot(thumbnail: boolean = true): Promise<ScreenshotResponse> {
    // Add thumbnail query param for low-quality screenshots
    const response = await this.client.get<ScreenshotResponse>('/screenshot', {
      params: thumbnail ? { thumbnail: 'true' } : undefined,
    });
    return response.data;
  }

  async getHistory(): Promise<TaskRecord[]> {
    const response = await this.client.get<TaskRecord[]>('/history');
    return response.data;
  }

  async clearHistory(): Promise<void> {
    await this.client.delete('/history');
  }

  // Dynamic port detection (for development)
  async detectBackendPort(): Promise<number> {
    const commonPorts = [8080, 8081, 8082, 3000, 3001];
    for (const port of commonPorts) {
      try {
        const testClient = axios.create({
          baseURL: `http://localhost:${port}/api/agent`,
          timeout: 1000,
        });
        await testClient.get('/status');
        this.backendPort = port;
        this.client.defaults.baseURL = `http://localhost:${port}/api/agent`;
        console.log(`[API] Found backend on port ${port}`);
        return port;
      } catch {
        // Continue to next port
      }
    }
    throw new Error('No backend server found on common ports');
  }

  // Voice Chat API
  async voiceChat(audioFile: File, screenshot?: File): Promise<{
    success: boolean;
    user_text: string;
    agent_text: string;
    agent_audio: string;
    duration_ms: number;
  }> {
    const formData = new FormData();
    formData.append('file', audioFile);
    if (screenshot) {
      formData.append('screenshot', screenshot);
    }

    const response = await this.client.post<{
      success: boolean;
      user_text: string;
      agent_text: string;
      agent_audio: string;
      duration_ms: number;
    }>('/voice-chat', formData, {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
    });

    return response.data;
  }
}

export const agentApi = new AgentApi();
