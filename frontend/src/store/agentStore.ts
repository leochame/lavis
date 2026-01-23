import { create } from 'zustand';
import type { PlanStepEvent, WorkflowState, ConnectionStatus } from '../hooks/useWebSocket';

interface AgentState {
  connectionStatus: ConnectionStatus;
  workflow: WorkflowState;
  lastEvent: PlanStepEvent | null;
}

interface AgentActions {
  setConnectionStatus: (status: ConnectionStatus) => void;
  setWorkflow: (workflow: WorkflowState) => void;
  setLastEvent: (event: PlanStepEvent | null) => void;
}

export const useAgentStore = create<AgentState & AgentActions>((set) => ({
  connectionStatus: 'disconnected',
  workflow: {
    planId: null,
    userGoal: null,
    steps: [],
    progress: 0,
    status: 'idle',
    currentStepId: null,
    logs: [],
  },
  lastEvent: null,
  setConnectionStatus: (connectionStatus) => set({ connectionStatus }),
  setWorkflow: (workflow) => set({ workflow }),
  setLastEvent: (event) => set({ lastEvent: event }),
}));

