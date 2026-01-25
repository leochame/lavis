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

    // 使用 127.0.0.1 而不是 localhost，避免 DNS 解析问题
    // 这在 Electron 环境中可以防止 DNS 相关的崩溃
    this.client = axios.create({
      baseURL: `http://127.0.0.1:${this.backendPort}/api/agent`,
      // 关键修改 1: 将默认超时时间设置为 0 (无限制)
      // 这解决了 "前端超时不再存在" 的需求，允许长时间运行的 Agent 任务
      timeout: 0,
      headers: {
        'Content-Type': 'application/json',
      },
    });

    // Request interceptor
    this.client.interceptors.request.use((config) => {
      return config;
    });

    // Response interceptor for error handling
    this.client.interceptors.response.use(
      (response) => response,
      (error) => {
        // 忽略被取消的请求造成的错误
        if (axios.isCancel(error)) {
          return Promise.reject(error);
        }
        
        console.error('[API] Error:', error.message);
        this.consecutiveErrors++;
        if (this.consecutiveErrors >= 5) {
          // 连续失败5次后，降低心跳频率，减轻浏览器和服务器负担
          this.stopHeartbeat();
          if (this.heartbeatCallback) {
            this.startHeartbeat(this.heartbeatCallback, 5000); 
          }
        }
        return Promise.reject(error);
      }
    );
  }

  // Heartbeat mechanism with adaptive polling
  startHeartbeat(callback: (status: AgentStatus | null) => void, intervalMs: number = 2000): void {
    this.heartbeatCallback = callback;
    this.checkStatus(); // Initial check

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
      // 关键修改 2: 心跳检测需要快速失败，不能受全局 timeout: 0 的影响
      // 如果 5秒 内没有返回状态，认为此时服务不可用
      const response = await this.client.get<AgentStatus>('/status', { timeout: 5000 });
      const newStatus = response.data;

      // Only callback if status actually changed (deep comparison)
      if (JSON.stringify(newStatus) !== JSON.stringify(this.lastStatus)) {
        this.heartbeatCallback?.(newStatus);
        this.lastStatus = newStatus;
        this.consecutiveErrors = 0; // Reset error counter on success
      }
    } catch {
      // 心跳失败时，不传递 null，而是保持上一次的状态或传递错误标识，
      // 这里为了兼容性保持传递 null，但在 UI 层可以做更平滑的处理
      this.heartbeatCallback?.(null);
      this.consecutiveErrors++;
    }
  }

  // Core APIs
  async chat(request: ChatRequest): Promise<ChatResponse> {
    // chat 接口可能会运行很久，使用默认的 timeout: 0
    const response = await this.client.post<ChatResponse>('/chat', request);
    return response.data;
  }

  async executeTask(request: TaskRequest): Promise<TaskResponse> {
    // task 接口可能会运行很久，使用默认的 timeout: 0
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
    return this.client.get<AgentStatus>('/status', { timeout: 5000 });
  }

  // Utilities
  async getScreenshot(thumbnail: boolean = true): Promise<ScreenshotResponse> {
    const response = await this.client.get<ScreenshotResponse>('/screenshot', {
      params: thumbnail ? { thumbnail: 'true' } : undefined,
      timeout: 10000, // 截图给予 10s 超时
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
          baseURL: `http://127.0.0.1:${port}/api/agent`,
          timeout: 2000, // 检测端口时需要快速超时
        });
        await testClient.get('/status');
        this.backendPort = port;
        this.client.defaults.baseURL = `http://127.0.0.1:${port}/api/agent`;
        return port;
      } catch {
        // Continue to next port
      }
    }
    throw new Error('No backend server found on common ports');
  }

  // Get WebSocket URL based on detected backend port
  getWebSocketUrl(): string {
    // 使用 127.0.0.1 而不是 localhost，避免 DNS 解析问题
    return `ws://127.0.0.1:${this.backendPort}/ws/agent`;
  }

  // Voice Chat API (异步 TTS 版本)
  // 返回文本响应，音频通过 WebSocket 异步推送
  async voiceChat(audioFile: File, wsSessionId?: string, screenshot?: File): Promise<{
    success: boolean;
    user_text: string;
    agent_text: string;
    request_id: string;
    audio_pending: boolean;
    duration_ms: number;
  }> {
    const formData = new FormData();
    formData.append('file', audioFile);
    if (wsSessionId) {
      formData.append('ws_session_id', wsSessionId);
    }
    if (screenshot) {
      formData.append('screenshot', screenshot);
    }

    try {
      const response = await this.client.post<{
        success: boolean;
        user_text: string;
        agent_text: string;
        request_id: string;
        audio_pending: boolean;
        duration_ms: number;
      }>('/voice-chat', formData, {
        headers: {
          'Content-Type': 'multipart/form-data',
        },
        // 语音处理需要合理超时，避免状态卡在 processing
        // 5 分钟超时：足够处理大部分语音请求，但不会无限等待
        timeout: 300000,
      });

      return response.data;
    } catch (error) {
      if (error instanceof Error) {
        console.error('[API] Voice chat failed:', error.message);
      }
      throw error;
    }
  }

  // TTS API (Text-to-Speech)
  // 调用后端 TTS 代理端点，将文本转换为音频
  // 配置统一在后端管理，前端无需配置 API key
  async tts(text: string): Promise<{
    success: boolean;
    audio: string; // Base64 encoded audio
    format: string;
    duration_ms: number;
  }> {
    try {
      const response = await this.client.post<{
        success: boolean;
        audio: string;
        format: string;
        duration_ms: number;
      }>('/tts', { text }, {
        timeout: 30000, // TTS 可能需要较长时间，设置 30s 超时
      });

      return response.data;
    } catch (error) {
      if (error instanceof Error) {
        console.error('[API] TTS failed:', error.message);
      }
      throw error;
    }
  }
}

export const agentApi = new AgentApi();