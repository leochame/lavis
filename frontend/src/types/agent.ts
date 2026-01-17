export interface AgentStatus {
  available: boolean;
  model: string;
  orchestrator_state?: string;
  current_plan_progress?: number;
}

export interface ChatRequest {
  message: string;
}

export interface ChatResponse {
  success: boolean;
  response: string;
  duration_ms: number;
}

export interface TaskRequest {
  goal: string;
  task?: string; // Legacy parameter
}

export interface TaskResponse {
  success: boolean;
  message: string;
  duration_ms: number;
  plan_summary?: string;
  steps_total?: number;
  execution_summary?: string;
}

export interface TaskRecord {
  id: string;
  type: string;
  input: string;
  output: string;
  success: boolean;
  durationMs: number;
  timestamp: string;
}

export interface ScreenshotResponse {
  success: boolean;
  image: string; // base64
  size: {
    width: number;
    height: number;
  };
}
