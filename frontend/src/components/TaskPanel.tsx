import { useRef, useEffect } from 'react';
import type { AgentStatus, TaskPlan } from '../types/agent';
import './TaskPanel.css';

export function TaskPanel({ status, onEmergencyStop }: { status: AgentStatus | null; onEmergencyStop: () => void }) {
  const scrollRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to the running step
  useEffect(() => {
    if (status?.current_plan && scrollRef.current) {
      // Find the running or pending step index
      const activeStepIndex = status.current_plan.steps.findIndex(
        step => step.status === 'IN_PROGRESS' || step.status === 'PENDING'
      );
      
      if (activeStepIndex !== -1) {
        const stepElement = scrollRef.current.children[activeStepIndex] as HTMLElement;
        if (stepElement) {
          stepElement.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }
      }
    }
  }, [status?.current_plan]);

  const isExecuting = status?.orchestrator_state?.includes('EXECUTING');
  const isThinking = status?.orchestrator_state?.includes('THINKING') ||
                    status?.orchestrator_state?.includes('PLANNING');
  const progress = status?.current_plan_progress || 0;
  const plan = status?.current_plan;

  // Mock plan for thinking state if real plan is not yet available
  const displayPlan = plan || (isThinking ? {
    steps: [
      { id: 1, description: 'Analyzing user request...', status: 'IN_PROGRESS' },
      { id: 2, description: 'Generating execution plan...', status: 'PENDING' },
      { id: 3, description: 'Validating steps...', status: 'PENDING' }
    ]
  } as unknown as TaskPlan : null);

  if (!displayPlan && !isExecuting && !isThinking) return null;

  return (
    <div className="task-panel">
      <div className="task-panel__header">
        <h3>System Operations</h3>
        <button
          className={`task-panel__stop ${isExecuting ? 'task-panel__stop--active' : ''}`}
          onClick={onEmergencyStop}
          disabled={!isExecuting}
        >
          ABORT
        </button>
      </div>

      <div className="task-panel__progress">
        <div className="task-panel__progress-bar">
          <div
            className="task-panel__progress-fill"
            style={{ width: `${progress}%` }}
          ></div>
        </div>
        <span className="task-panel__progress-text">{progress}%</span>
      </div>

      {displayPlan && (
        <div className="task-panel__steps" ref={scrollRef}>
          {displayPlan.steps.map((step) => {
            return (
              <div
                key={step.id}
                className={`task-panel__step task-panel__step--${step.status}`}
              >
                <span className="task-panel__step-number">
                  {step.id.toString().padStart(2, '0')}
                </span>
                <span className="task-panel__step-text">{step.description}</span>
                
                <span className="task-panel__step-status-icon">
                  {step.status === 'SUCCESS' && '✓'}
                  {step.status === 'FAILED' && '✗'}
                  {step.status === 'IN_PROGRESS' && '▶'}
                  {step.status === 'PENDING' && '•'}
                  {step.status === 'SKIPPED' && '↷'}
                </span>

                {step.status === 'IN_PROGRESS' && <div className="task-panel__step-pulse"></div>}
              </div>
            );
          })}
        </div>
      )}

      {status?.current_plan?.status === 'COMPLETED' && (
        <div className="task-panel__completed">
          <span>Target Achieved</span>
        </div>
      )}
    </div>
  );
}
