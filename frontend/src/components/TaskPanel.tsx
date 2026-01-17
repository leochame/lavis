import { useState, useEffect } from 'react';
import type { AgentStatus } from '../types/agent';
import './TaskPanel.css';

interface TaskStep {
  step: string;
  status: 'pending' | 'running' | 'completed' | 'failed';
  error?: string;
}

export function TaskPanel({ status, onEmergencyStop }: { status: AgentStatus | null; onEmergencyStop: () => void }) {
  const [currentPlan, setCurrentPlan] = useState<{ steps: string[] } | null>(null);

  // Update steps based on orchestrator state
  useEffect(() => {
    if (status?.orchestrator_state) {
      const state = status.orchestrator_state;

      // Parse orchestrator state to extract steps
      if (state.includes('PLANNING')) {
        setCurrentPlan({ steps: ['Analyzing goal...', 'Generating plan...', 'Reviewing steps...'] });
      } else if (state.includes('EXECUTING')) {
        setCurrentPlan({ steps: ['Executing step 1...', 'Executing step 2...', 'Executing step 3...'] });
      } else if (state.includes('REFLECTING')) {
        setCurrentPlan({ steps: ['Observing result...', 'Analyzing outcome...', 'Planning correction...'] });
      } else {
        setCurrentPlan(null);
      }
    }
  }, [status]);

  const isExecuting = status?.orchestrator_state?.includes('EXECUTING');
  const isThinking = status?.orchestrator_state?.includes('THINKING') ||
                    status?.orchestrator_state?.includes('PLANNING');
  const progress = status?.current_plan_progress || 0;

  return (
    <div className="task-panel">
      <div className="task-panel__header">
        <h3>Task Progress</h3>
        <button
          className={`task-panel__stop ${isExecuting ? 'task-panel__stop--active' : ''}`}
          onClick={onEmergencyStop}
          disabled={!isExecuting}
        >
          🛑 Stop
        </button>
      </div>

      {isThinking && !isExecuting && (
        <div className="task-panel__status">
          <div className="task-panel__spinner"></div>
          <span>Planning...</span>
        </div>
      )}

      {isExecuting && (
        <>
          <div className="task-panel__progress">
            <div className="task-panel__progress-bar">
              <div
                className="task-panel__progress-fill"
                style={{ width: `${progress}%` }}
              ></div>
            </div>
            <span className="task-panel__progress-text">{progress}%</span>
          </div>

          {currentPlan && (
            <div className="task-panel__steps">
              {currentPlan.steps.map((step, index) => {
                const stepProgress = progress / (100 / currentPlan.steps.length);
                const stepStatus: TaskStep['status'] =
                  stepProgress > index ? 'completed' :
                  stepProgress > index - 1 ? 'running' : 'pending';

                return (
                  <div
                    key={index}
                    className={`task-panel__step task-panel__step--${stepStatus}`}
                  >
                    <span className="task-panel__step-number">{index + 1}</span>
                    <span className="task-panel__step-text">{step}</span>
                    {stepStatus === 'running' && <div className="task-panel__step-pulse"></div>}
                  </div>
                );
              })}
            </div>
          )}
        </>
      )}

      {status && !isExecuting && !isThinking && progress === 100 && (
        <div className="task-panel__completed">
          <span className="task-panel__completed-icon">✅</span>
          <span>Task completed</span>
        </div>
      )}
    </div>
  );
}
