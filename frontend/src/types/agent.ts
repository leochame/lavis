export interface PlanStep {
  id: number;
  description: string;
  type?: string; // 可选字段，不再强制要求
  status: 'PENDING' | 'IN_PROGRESS' | 'SUCCESS' | 'FAILED' | 'SKIPPED';
  resultSummary?: string;
}

export interface TaskPlan {
  planId: string;
  userGoal: string;
  steps: PlanStep[];
  currentStepIndex: number;
  status: 'CREATED' | 'EXECUTING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
}

export interface AgentStatus {
  available: boolean;
  model: string;
  orchestrator_state?: string;
  current_plan_progress?: number;
  current_plan?: TaskPlan;
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
