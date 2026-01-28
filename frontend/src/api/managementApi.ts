import axios, { type AxiosInstance } from 'axios';

// Skills Types
export interface Skill {
  id: string;
  name: string;
  description: string | null;
  category: string | null;
  version: string | null;
  author: string | null;
  content: string;
  command: string;
  enabled: boolean;
  installSource: string | null;
  createdAt: string;
  updatedAt: string;
  lastUsedAt: string | null;
  useCount: number;
}

export interface CreateSkillRequest {
  name: string;
  description?: string;
  category?: string;
  version?: string;
  author?: string;
  content: string;
  command: string;
}

export interface UpdateSkillRequest {
  name?: string;
  description?: string;
  category?: string;
  version?: string;
  author?: string;
  content?: string;
  command?: string;
  enabled?: boolean;
}

export interface ExecuteSkillRequest {
  params?: Record<string, string>;
}

export interface ExecuteSkillResponse {
  success: boolean;
  output: string | null;
  error: string | null;
  durationMs: number;
}

// Scheduler Types
export interface ScheduledTask {
  id: string;
  name: string;
  description: string | null;
  cronExpression: string;
  command: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
  lastRunAt: string | null;
  nextRunAt: string | null;
  runCount: number;
  lastStatus: string | null;
}

export interface CreateTaskRequest {
  name: string;
  description?: string;
  cronExpression: string;
  command: string;
}

export interface UpdateTaskRequest {
  name?: string;
  description?: string;
  cronExpression?: string;
  command?: string;
  enabled?: boolean;
}

export interface TaskRunLog {
  id: string;
  taskId: string;
  startTime: string;
  endTime: string | null;
  status: string;
  output: string | null;
  error: string | null;
  durationMs: number;
}

class ManagementApi {
  private client: AxiosInstance;
  private backendPort: number = 8080;

  constructor(port?: number) {
    this.backendPort = port ?? 8080;
    this.client = axios.create({
      baseURL: `http://127.0.0.1:${this.backendPort}/api`,
      timeout: 30000,
      headers: {
        'Content-Type': 'application/json',
      },
    });
  }

  setPort(port: number) {
    this.backendPort = port;
    this.client.defaults.baseURL = `http://127.0.0.1:${port}/api`;
  }

  // ==================== Skills API ====================

  async getSkills(enabled?: boolean, category?: string): Promise<Skill[]> {
    const params: Record<string, string> = {};
    if (enabled !== undefined) params.enabled = String(enabled);
    if (category) params.category = category;
    const response = await this.client.get<Skill[]>('/skills', { params });
    return response.data;
  }

  async getSkill(id: string): Promise<Skill> {
    const response = await this.client.get<Skill>(`/skills/${id}`);
    return response.data;
  }

  async getSkillByName(name: string): Promise<Skill> {
    const response = await this.client.get<Skill>(`/skills/by-name/${name}`);
    return response.data;
  }

  async createSkill(request: CreateSkillRequest): Promise<Skill> {
    const response = await this.client.post<Skill>('/skills', request);
    return response.data;
  }

  async updateSkill(id: string, request: UpdateSkillRequest): Promise<Skill> {
    const response = await this.client.put<Skill>(`/skills/${id}`, request);
    return response.data;
  }

  async deleteSkill(id: string): Promise<void> {
    await this.client.delete(`/skills/${id}`);
  }

  async executeSkill(id: string, request?: ExecuteSkillRequest): Promise<ExecuteSkillResponse> {
    const response = await this.client.post<ExecuteSkillResponse>(
      `/skills/${id}/execute`,
      request ?? {}
    );
    return response.data;
  }

  async reloadSkills(): Promise<{ success: boolean; message: string; count: number }> {
    const response = await this.client.post<{ success: boolean; message: string; count: number }>(
      '/skills/reload'
    );
    return response.data;
  }

  async getSkillCategories(): Promise<string[]> {
    const response = await this.client.get<string[]>('/skills/categories');
    return response.data;
  }

  // ==================== Scheduler API ====================

  async getScheduledTasks(): Promise<ScheduledTask[]> {
    const response = await this.client.get<{ success: boolean; tasks: ScheduledTask[] }>('/scheduler/tasks');
    return response.data.tasks || [];
  }

  async getScheduledTask(id: string): Promise<ScheduledTask> {
    const response = await this.client.get<{ success: boolean; task: ScheduledTask }>(`/scheduler/tasks/${id}`);
    return response.data.task;
  }

  async createScheduledTask(request: CreateTaskRequest): Promise<ScheduledTask> {
    const response = await this.client.post<{ success: boolean; task: ScheduledTask }>('/scheduler/tasks', request);
    return response.data.task;
  }

  async updateScheduledTask(id: string, request: UpdateTaskRequest): Promise<ScheduledTask> {
    const response = await this.client.put<{ success: boolean; task: ScheduledTask }>(`/scheduler/tasks/${id}`, request);
    return response.data.task;
  }

  async deleteScheduledTask(id: string): Promise<void> {
    await this.client.delete(`/scheduler/tasks/${id}`);
  }

  async runScheduledTaskNow(id: string): Promise<{ success: boolean; message: string }> {
    const response = await this.client.post<{ success: boolean; message: string }>(`/scheduler/tasks/${id}/start`);
    return response.data;
  }

  async getTaskRunLogs(taskId: string, limit?: number): Promise<TaskRunLog[]> {
    const params: Record<string, string> = {};
    if (limit) params.limit = String(limit);
    const response = await this.client.get<{ success: boolean; history: TaskRunLog[] }>(
      `/scheduler/tasks/${taskId}/history`,
      { params }
    );
    return response.data.history || [];
  }

  async pauseScheduledTask(id: string): Promise<ScheduledTask> {
    const response = await this.client.post<{ success: boolean; task: ScheduledTask }>(`/scheduler/tasks/${id}/stop`);
    return response.data.task;
  }

  async resumeScheduledTask(id: string): Promise<ScheduledTask> {
    const response = await this.client.post<{ success: boolean; task: ScheduledTask }>(`/scheduler/tasks/${id}/start`);
    return response.data.task;
  }
}

export const managementApi = new ManagementApi();
