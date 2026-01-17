import { useState, useEffect } from 'react';
import type { AgentStatus } from '../types/agent';
import './Capsule.css';

interface CapsuleProps {
  status: AgentStatus | null;
  onClick: () => void;
}

export function Capsule({ status, onClick }: CapsuleProps) {
  const [isBreathing, setIsBreathing] = useState(false);

  // Detect agent state for visual feedback
  const getAgentState = (): 'idle' | 'thinking' | 'executing' | 'error' => {
    if (!status?.available) return 'error';
    const orchestratorState = status.orchestrator_state;
    if (orchestratorState?.includes('EXECUTING')) return 'executing';
    if (orchestratorState?.includes('THINKING') || orchestratorState?.includes('PLANNING')) return 'thinking';
    return 'idle';
  };

  const agentState = getAgentState();

  // Breathing animation for thinking/executing states
  useEffect(() => {
    if (agentState === 'thinking' || agentState === 'executing') {
      setIsBreathing(true);
    } else {
      setIsBreathing(false);
    }
  }, [agentState]);

  return (
    <div
      className={`capsule capsule--${agentState} ${isBreathing ? 'capsule--breathing' : ''}`}
      onClick={onClick}
      title={agentState === 'idle' ? 'Click to open' : agentState}
    >
      <div className="capsule__core"></div>
    </div>
  );
}
