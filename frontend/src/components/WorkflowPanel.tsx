import { useRef, useEffect } from 'react';
import type { WorkflowState } from '../hooks/useWebSocket';
import './WorkflowPanel.css';

interface WorkflowPanelProps {
  workflow: WorkflowState;
  connected: boolean;
  onStop?: () => void;
}

export function WorkflowPanel({ workflow, connected, onStop }: WorkflowPanelProps) {
  const stepsRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to current step
  useEffect(() => {
    if (workflow.currentStepId && stepsRef.current) {
      const activeStep = stepsRef.current.querySelector('.workflow-step--active');
      activeStep?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
  }, [workflow.currentStepId]);

  if (workflow.status === 'idle' && workflow.steps.length === 0) {
    return null;
  }

  const getStatusIcon = (status: string) => {
    switch (status) {
      case 'SUCCESS': return '✓';
      case 'FAILED': return '✗';
      case 'IN_PROGRESS': return '▶';
      case 'SKIPPED': return '↷';
      default: return '○';
    }
  };

  const getStatusClass = (status: string) => {
    switch (status) {
      case 'SUCCESS': return 'workflow-step--success';
      case 'FAILED': return 'workflow-step--failed';
      case 'IN_PROGRESS': return 'workflow-step--active';
      case 'SKIPPED': return 'workflow-step--skipped';
      default: return 'workflow-step--pending';
    }
  };

  return (
    <div className="workflow-panel">
      {/* Header */}
      <div className="workflow-header">
        <div className="workflow-header__left">
          <div className={`workflow-status-dot ${connected ? 'workflow-status-dot--connected' : ''}`} />
          <span className="workflow-header__title">WORKFLOW</span>
        </div>
        {workflow.status === 'executing' && onStop && (
          <button className="workflow-stop-btn" onClick={onStop}>
            ABORT
          </button>
        )}
      </div>

      {/* Goal */}
      {workflow.userGoal && (
        <div className="workflow-goal">
          <span className="workflow-goal__label">TARGET:</span>
          <span className="workflow-goal__text">{workflow.userGoal}</span>
        </div>
      )}

      {/* Progress Bar */}
      <div className="workflow-progress">
        <div className="workflow-progress__bar">
          <div
            className="workflow-progress__fill"
            style={{ width: `${workflow.progress}%` }}
          />
          <div className="workflow-progress__glow" style={{ left: `${workflow.progress}%` }} />
        </div>
        <span className="workflow-progress__text">{workflow.progress}%</span>
      </div>

      {/* Steps */}
      <div className="workflow-steps" ref={stepsRef}>
        {workflow.steps.map((step, index) => (
          <div
            key={step.id}
            className={`workflow-step ${getStatusClass(step.status)}`}
          >
            <div className="workflow-step__connector">
              {index > 0 && <div className="workflow-step__line workflow-step__line--top" />}
              <div className="workflow-step__dot">
                <span className="workflow-step__icon">{getStatusIcon(step.status)}</span>
              </div>
              {index < workflow.steps.length - 1 && (
                <div className="workflow-step__line workflow-step__line--bottom" />
              )}
            </div>
            
            <div className="workflow-step__content">
              <div className="workflow-step__header">
                <span className="workflow-step__number">{String(step.id).padStart(2, '0')}</span>
                {step.type && <span className="workflow-step__type">{step.type}</span>}
                {step.executionTimeMs && (
                  <span className="workflow-step__time">{step.executionTimeMs}ms</span>
                )}
              </div>
              <div className="workflow-step__description">{step.description}</div>
              {step.resultSummary && step.status !== 'PENDING' && (
                <div className="workflow-step__result">{step.resultSummary}</div>
              )}
            </div>

            {step.status === 'IN_PROGRESS' && <div className="workflow-step__pulse" />}
          </div>
        ))}
      </div>

      {/* Status */}
      {workflow.status === 'completed' && (
        <div className="workflow-complete">
          <span className="workflow-complete__icon">◆</span>
          <span>MISSION ACCOMPLISHED</span>
        </div>
      )}

      {workflow.status === 'failed' && (
        <div className="workflow-failed">
          <span className="workflow-failed__icon">⚠</span>
          <span>MISSION FAILED</span>
        </div>
      )}
    </div>
  );
}

