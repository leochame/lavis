import { useRef, useEffect, useState } from 'react';
import type { WorkflowState, PlanStepEvent } from '../hooks/useWebSocket';
import type { ConnectionStatus } from '../hooks/useWebSocket';
import './BrainPanel.css';

interface BrainPanelProps {
  workflow: WorkflowState;
  connectionStatus: ConnectionStatus;
  onStop?: () => void;
}

/**
 * Brain Panel - Cursor/Claude Code style task flow visualization
 * Shows real-time AI thinking process and task execution
 */
export function BrainPanel({ workflow, connectionStatus, onStop }: BrainPanelProps) {
  const stepsRef = useRef<HTMLDivElement>(null);
  const logsRef = useRef<HTMLDivElement>(null);
  const [expandedSteps, setExpandedSteps] = useState<Set<number>>(new Set());
  const [activeTab, setActiveTab] = useState<'steps' | 'logs'>('steps');

  // Auto-scroll to current step
  useEffect(() => {
    if (workflow.currentStepId && stepsRef.current) {
      const activeStep = stepsRef.current.querySelector('.brain-step--active');
      activeStep?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
  }, [workflow.currentStepId]);

  // Auto-scroll logs
  useEffect(() => {
    if (logsRef.current && workflow.logs.length > 0) {
      logsRef.current.scrollTop = logsRef.current.scrollHeight;
    }
  }, [workflow.logs]);

  // Auto-expand active step
  useEffect(() => {
    if (workflow.currentStepId) {
      setExpandedSteps(prev => new Set([...prev, workflow.currentStepId!]));
    }
  }, [workflow.currentStepId]);

  const toggleStep = (stepId: number) => {
    setExpandedSteps(prev => {
      const next = new Set(prev);
      if (next.has(stepId)) {
        next.delete(stepId);
      } else {
        next.add(stepId);
      }
      return next;
    });
  };

  const getStatusIcon = (status: string) => {
    switch (status) {
      case 'SUCCESS': return '✓';
      case 'FAILED': return '✗';
      case 'IN_PROGRESS': return '●';
      case 'SKIPPED': return '○';
      default: return '○';
    }
  };

  const getStatusLabel = (status: string) => {
    switch (status) {
      case 'SUCCESS': return 'Done';
      case 'FAILED': return 'Failed';
      case 'IN_PROGRESS': return 'Running';
      case 'SKIPPED': return 'Skipped';
      default: return 'Pending';
    }
  };

  const getConnectionStatusText = () => {
    switch (connectionStatus) {
      case 'connected': return 'Connected';
      case 'connecting': return 'Connecting...';
      default: return 'Disconnected';
    }
  };

  const getWorkflowStatusText = () => {
    switch (workflow.status) {
      case 'planning': return 'Planning...';
      case 'executing': return 'Executing';
      case 'completed': return 'Completed';
      case 'failed': return 'Failed';
      default: return 'Idle';
    }
  };

  const formatTime = (ms: number) => {
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
  };

  const formatLogTime = (timestamp: number) => {
    return new Date(timestamp).toLocaleTimeString('en-US', {
      hour12: false,
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
  };

  const completedSteps = workflow.steps.filter(s => s.status === 'SUCCESS').length;
  const totalSteps = workflow.steps.length;

  return (
    <div className="brain-panel">
      {/* Header */}
      <div className="brain-header">
        <div className="brain-header__left">
          <div className={`brain-status-indicator brain-status-indicator--${connectionStatus}`} />
          <span className="brain-header__title">BRAIN</span>
          <span className="brain-header__connection">{getConnectionStatusText()}</span>
        </div>
        <div className="brain-header__right">
          {workflow.status === 'executing' && onStop && (
            <button className="brain-stop-btn" onClick={onStop}>
              Stop
            </button>
          )}
        </div>
      </div>

      {/* Status Bar */}
      {workflow.status !== 'idle' && (
        <div className="brain-status-bar">
          <div className="brain-status-bar__info">
            <span className={`brain-status-badge brain-status-badge--${workflow.status}`}>
              {getWorkflowStatusText()}
            </span>
            {totalSteps > 0 && (
              <span className="brain-status-bar__progress">
                {completedSteps}/{totalSteps} steps
              </span>
            )}
          </div>
          <div className="brain-progress-bar">
            <div
              className="brain-progress-bar__fill"
              style={{ width: `${workflow.progress}%` }}
            />
          </div>
        </div>
      )}

      {/* Goal */}
      {workflow.userGoal && (
        <div className="brain-goal">
          <span className="brain-goal__text">{workflow.userGoal}</span>
        </div>
      )}

      {/* Tabs */}
      <div className="brain-tabs">
        <button
          className={`brain-tab ${activeTab === 'steps' ? 'brain-tab--active' : ''}`}
          onClick={() => setActiveTab('steps')}
        >
          Steps {totalSteps > 0 && `(${totalSteps})`}
        </button>
        <button
          className={`brain-tab ${activeTab === 'logs' ? 'brain-tab--active' : ''}`}
          onClick={() => setActiveTab('logs')}
        >
          Logs {workflow.logs.length > 0 && `(${workflow.logs.length})`}
        </button>
      </div>

      {/* Content */}
      <div className="brain-content">
        {activeTab === 'steps' ? (
          <div className="brain-steps" ref={stepsRef}>
            {workflow.steps.length === 0 ? (
              <div className="brain-empty">
                <div className="brain-empty__mark" aria-hidden="true" />
                <div className="brain-empty__text">
                  {workflow.status === 'planning'
                    ? 'AI is analyzing and planning...'
                    : 'Waiting for task...'}
                </div>
                {workflow.status === 'planning' && (
                  <div className="brain-empty__loader">
                    <span></span><span></span><span></span>
                  </div>
                )}
              </div>
            ) : (
              workflow.steps.map((step, index) => (
                <StepItem
                  key={step.id}
                  step={step}
                  index={index}
                  isLast={index === workflow.steps.length - 1}
                  isExpanded={expandedSteps.has(step.id)}
                  onToggle={() => toggleStep(step.id)}
                  getStatusIcon={getStatusIcon}
                  getStatusLabel={getStatusLabel}
                  formatTime={formatTime}
                />
              ))
            )}
          </div>
        ) : (
          <div className="brain-logs" ref={logsRef}>
            {workflow.logs.length === 0 ? (
              <div className="brain-empty">
                <div className="brain-empty__text">No logs yet</div>
              </div>
            ) : (
              workflow.logs.map((log, index) => (
                <div key={index} className={`brain-log brain-log--${log.level.toLowerCase()}`}>
                  <span className="brain-log__time">{formatLogTime(log.timestamp)}</span>
                  <span className="brain-log__level">{log.level}</span>
                  <span className="brain-log__message">{log.message}</span>
                </div>
              ))
            )}
          </div>
        )}
      </div>

      {/* Footer Status */}
      {workflow.status === 'completed' && (
        <div className="brain-footer brain-footer--success">
          <span className="brain-footer__icon">✓</span>
          <span>Task completed successfully</span>
        </div>
      )}
      {workflow.status === 'failed' && (
        <div className="brain-footer brain-footer--error">
          <span className="brain-footer__icon">✗</span>
          <span>Task failed</span>
        </div>
      )}
    </div>
  );
}

/**
 * Step Item Component - Collapsible step with details
 */
interface StepItemProps {
  step: PlanStepEvent;
  index: number;
  isLast: boolean;
  isExpanded: boolean;
  onToggle: () => void;
  getStatusIcon: (status: string) => string;
  getStatusLabel: (status: string) => string;
  formatTime: (ms: number) => string;
}

function StepItem({
  step,
  index,
  isLast,
  isExpanded,
  onToggle,
  getStatusIcon,
  getStatusLabel,
  formatTime,
}: StepItemProps) {
  const isActive = step.status === 'IN_PROGRESS';
  const isSuccess = step.status === 'SUCCESS';
  const isFailed = step.status === 'FAILED';
  const isPending = step.status === 'PENDING';

  return (
    <div
      className={`brain-step ${isActive ? 'brain-step--active' : ''} ${isSuccess ? 'brain-step--success' : ''} ${isFailed ? 'brain-step--failed' : ''} ${isPending ? 'brain-step--pending' : ''}`}
    >
      {/* Timeline connector */}
      <div className="brain-step__timeline">
        <div className={`brain-step__dot ${isActive ? 'brain-step__dot--pulse' : ''}`}>
          {isActive ? (
            <div className="brain-step__spinner" />
          ) : (
            <span>{getStatusIcon(step.status)}</span>
          )}
        </div>
        {!isLast && <div className="brain-step__line" />}
      </div>

      {/* Content */}
      <div className="brain-step__content" onClick={onToggle}>
        <div className="brain-step__header">
          <span className="brain-step__number">{index + 1}</span>
          {step.type && <span className="brain-step__type">{step.type}</span>}
          <span className={`brain-step__status brain-step__status--${step.status.toLowerCase()}`}>
            {getStatusLabel(step.status)}
          </span>
          {step.executionTimeMs && (
            <span className="brain-step__time">{formatTime(step.executionTimeMs)}</span>
          )}
          <span className={`brain-step__chevron ${isExpanded ? 'brain-step__chevron--open' : ''}`}>
            ▸
          </span>
        </div>

        <div className="brain-step__description">{step.description}</div>

        {/* Expanded details */}
        {isExpanded && (
          <div className="brain-step__details">
            {step.resultSummary && (
              <div className="brain-step__detail">
                <span className="brain-step__detail-label">Result:</span>
                <span className="brain-step__detail-value">{step.resultSummary}</span>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
