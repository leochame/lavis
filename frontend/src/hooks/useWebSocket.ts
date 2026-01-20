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
  const [connected, setConnected] = useState(false);
  const [workflow, setWorkflow] = useState<WorkflowState>(INITIAL_STATE);
  const [lastEvent, setLastEvent] = useState<WorkflowEvent | null>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimeoutRef = useRef<number | null>(null);
  const connectRef = useRef<() => void>(() => {});

  const handleMessage = useCallback((message: WorkflowEvent) => {
    const { type, data } = message;

    switch (type) {
      case 'connected':
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

      case 'action_executed':
        // Could add to a log list for detailed view
        break;

      case 'hide_window':
        // No-op for web version - window hiding is not needed
        console.log('hide_window event received (no-op in web version)');
        break;

      case 'show_window':
        // No-op for web version - window showing is not needed
        console.log('show_window event received (no-op in web version)');
        break;

      case 'log':
        setWorkflow((prev) => ({
          ...prev,
          logs: [
            ...prev.logs.slice(-49), // Keep last 50 logs
            {
              level: data.level as string,
              message: data.message as string,
              timestamp: data.timestamp as number,
            },
          ],
        }));
        break;
    }
  }, []);

  const connect = useCallback(() => {
    if (wsRef.current?.readyState === WebSocket.OPEN) return;

    try {
      const ws = new WebSocket(url);
      wsRef.current = ws;

      ws.onopen = () => {
        setConnected(true);
        // Subscribe to workflow updates
        ws.send(JSON.stringify({ type: 'subscribe' }));
      };

      ws.onclose = () => {
        setConnected(false);
        // Reconnect after 3 seconds
        reconnectTimeoutRef.current = window.setTimeout(() => {
          if (connectRef.current) {
            connectRef.current();
          }
        }, 3000);
      };

      ws.onerror = () => {
        setConnected(false);
      };

      ws.onmessage = (event) => {
        try {
          const message = JSON.parse(event.data) as WorkflowEvent;
          setLastEvent(message);
          handleMessage(message);
        } catch (e) {
          console.error('Failed to parse WebSocket message:', e);
        }
      };
    } catch (e) {
      console.error('Failed to connect WebSocket:', e);
    }
  }, [url, handleMessage]);

  // Update ref whenever connect changes
  useEffect(() => {
    connectRef.current = connect;
  }, [connect]);

  const resetWorkflow = useCallback(() => {
    setWorkflow(INITIAL_STATE);
  }, []);

  useEffect(() => {
    connect();

    return () => {
      if (reconnectTimeoutRef.current) {
        clearTimeout(reconnectTimeoutRef.current);
      }
      wsRef.current?.close();
    };
  }, [connect]);

  return {
    connected,
    workflow,
    lastEvent,
    resetWorkflow,
  };
}

