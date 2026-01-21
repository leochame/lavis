import { useState, useEffect, useCallback, useRef } from 'react';

export interface WorkflowEvent {
  type: string;
  data: Record<string, unknown>;
  timestamp: number;
}

export interface PlanStepEvent {
  id: number;
  description: string;
  type: string;
  status: 'PENDING' | 'IN_PROGRESS' | 'SUCCESS' | 'FAILED' | 'SKIPPED';
  complexity?: number;
  definitionOfDone?: string;
  resultSummary?: string;
  executionTimeMs?: number;
}

export interface WorkflowState {
  planId: string | null;
  userGoal: string | null;
  steps: PlanStepEvent[];
  progress: number;
  status: 'idle' | 'planning' | 'executing' | 'completed' | 'failed';
  currentStepId: number | null;
  logs: Array<{ level: string; message: string; timestamp: number }>;
}

export type ConnectionStatus = 'disconnected' | 'connecting' | 'connected';

const INITIAL_STATE: WorkflowState = {
  planId: null,
  userGoal: null,
  steps: [],
  progress: 0,
  status: 'idle',
  currentStepId: null,
  logs: [],
};

export function useWebSocket(url: string = 'ws://localhost:8080/ws/agent') {
  const [status, setStatus] = useState<ConnectionStatus>('disconnected');
  const [workflow, setWorkflow] = useState<WorkflowState>(INITIAL_STATE);
  const [lastEvent, setLastEvent] = useState<WorkflowEvent | null>(null);
  
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimeoutRef = useRef<number | null>(null);
  const retryCountRef = useRef(0);
  const isUnmountedRef = useRef(false);

  // 消息处理逻辑
  const handleMessage = useCallback((message: WorkflowEvent) => {
    const { type, data } = message;

    switch (type) {
      case 'connected':
        console.log('[WS] Server confirmed connection');
        break;

      case 'plan_created':
        setWorkflow((prev) => ({
          ...prev,
          planId: data.planId as string,
          userGoal: data.userGoal as string,
          steps: (data.steps as PlanStepEvent[]) || [],
          progress: 0,
          status: 'planning',
          currentStepId: null,
        }));
        break;

      case 'step_started':
        setWorkflow((prev) => ({
          ...prev,
          status: 'executing',
          currentStepId: data.stepId as number,
          progress: data.progress as number,
          steps: prev.steps.map((step) =>
            step.id === data.stepId ? { ...step, status: 'IN_PROGRESS' } : step
          ),
        }));
        break;

      case 'step_completed':
        setWorkflow((prev) => ({
          ...prev,
          progress: data.progress as number,
          steps: prev.steps.map((step) =>
            step.id === data.stepId
              ? {
                  ...step,
                  status: 'SUCCESS',
                  resultSummary: data.resultSummary as string,
                  executionTimeMs: data.executionTimeMs as number,
                }
              : step
          ),
        }));
        break;

      case 'step_failed':
        setWorkflow((prev) => ({
          ...prev,
          progress: data.progress as number,
          steps: prev.steps.map((step) =>
            step.id === data.stepId
              ? { ...step, status: 'FAILED', resultSummary: data.reason as string }
              : step
          ),
        }));
        break;

      case 'plan_completed':
        setWorkflow((prev) => ({
          ...prev,
          status: 'completed',
          progress: 100,
        }));
        break;

      case 'plan_failed':
        setWorkflow((prev) => ({
          ...prev,
          status: 'failed',
        }));
        break;

      case 'thinking':
        setWorkflow((prev) => ({
          ...prev,
          status: 'planning',
        }));
        break;

      case 'log':
        setWorkflow((prev) => ({
          ...prev,
          logs: [
            ...prev.logs.slice(-49),
            {
              level: data.level as string,
              message: data.message as string,
              timestamp: data.timestamp as number,
            },
          ],
        }));
        break;
        
      default:
        // Handle custom events or unknown events silently
        break;
    }
  }, []);

  const connect = useCallback(() => {
    if (wsRef.current?.readyState === WebSocket.OPEN || wsRef.current?.readyState === WebSocket.CONNECTING) return;
    if (isUnmountedRef.current) return;

    setStatus('connecting');

    try {
      console.log(`[WS] Connecting to ${url}... (Attempt ${retryCountRef.current + 1})`);
      const ws = new WebSocket(url);
      wsRef.current = ws;

      ws.onopen = () => {
        if (isUnmountedRef.current) {
            ws.close();
            return;
        }
        console.log('[WS] Connected');
        setStatus('connected');
        retryCountRef.current = 0; // 重置重试计数
        // Subscribe to workflow updates
        ws.send(JSON.stringify({ type: 'subscribe' }));
      };

      ws.onclose = (event) => {
        if (isUnmountedRef.current) return;
        
        setStatus('disconnected');
        console.log(`[WS] Disconnected (Code: ${event.code})`);
        
        // 增强交互：指数退避重连算法
        // 延时: 1s, 2s, 4s, 8s, 16s, max 30s
        const backoffDelay = Math.min(1000 * Math.pow(2, retryCountRef.current), 30000);
        retryCountRef.current++;

        console.log(`[WS] Reconnecting in ${backoffDelay}ms...`);
        reconnectTimeoutRef.current = window.setTimeout(() => {
          connect();
        }, backoffDelay);
      };

      ws.onerror = (error) => {
        console.error('[WS] Error:', error);
        // onerror 之后通常会触发 onclose，所以重连逻辑放在 onclose
      };

      ws.onmessage = (event) => {
        try {
          const message = JSON.parse(event.data) as WorkflowEvent;
          setLastEvent(message);
          handleMessage(message);
        } catch (e) {
          console.error('[WS] Failed to parse message:', e);
        }
      };
    } catch (e) {
      console.error('[WS] Connection failed:', e);
      setStatus('disconnected');
    }
  }, [url, handleMessage]);

  // 发送消息的方法（增强交互性）
  const sendMessage = useCallback((type: string, data: Record<string, unknown> = {}) => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify({ type, data, timestamp: Date.now() }));
    } else {
      console.warn('[WS] Cannot send message, not connected');
    }
  }, []);

  const resetWorkflow = useCallback(() => {
    setWorkflow(INITIAL_STATE);
  }, []);

  useEffect(() => {
    isUnmountedRef.current = false;
    connect();

    return () => {
      isUnmountedRef.current = true;
      if (reconnectTimeoutRef.current) {
        clearTimeout(reconnectTimeoutRef.current);
      }
      if (wsRef.current) {
        wsRef.current.close();
      }
    };
  }, [connect]);

  return {
    connected: status === 'connected',
    status, // 暴露具体的连接状态 ('connecting' | 'connected' | 'disconnected')
    workflow,
    lastEvent,
    resetWorkflow,
    sendMessage, // 暴露发送方法
  };
}