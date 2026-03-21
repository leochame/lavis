import { useMemo } from 'react';
import type { AgentStatus } from '../../types/agent';
import type { WorkflowState } from '../../hooks/useWebSocket';

interface UseAppWorkingStateParams {
  workflow: WorkflowState;
  isTtsGenerating: boolean;
  status: AgentStatus | null;
}

export function useAppWorkingState({
  workflow,
  isTtsGenerating,
  status,
}: UseAppWorkingStateParams) {
  return useMemo(() => {
    return (
      workflow.status === 'executing' ||
      workflow.status === 'planning' ||
      isTtsGenerating ||
      status?.orchestrator_state?.includes('EXECUTING') ||
      status?.orchestrator_state?.includes('PLANNING') ||
      status?.orchestrator_state?.includes('THINKING')
    );
  }, [workflow.status, isTtsGenerating, status?.orchestrator_state]);
}
