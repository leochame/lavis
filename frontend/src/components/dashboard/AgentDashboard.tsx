import { useEffect } from 'react';
import type { AgentStatus } from '../../types/agent';
import type { UseGlobalVoiceReturn } from '../../hooks/useGlobalVoice';
import type { WorkflowState, ConnectionStatus } from '../../hooks/useWebSocket';
import { AgentSidebar } from './AgentSidebar';
import { AgentPanels } from './AgentPanels';
import { usePanelStore } from '../../store/panelStore';
import './AgentDashboard.css';

interface AgentDashboardProps {
  status: AgentStatus | null;
  globalVoice: UseGlobalVoiceReturn;
  wsConnected: boolean;
  wsStatus: ConnectionStatus;
  workflow: WorkflowState;
  resetWorkflow: () => void;
  sendMessage: (type: string, data?: Record<string, unknown>) => void;
  onClose: () => void;
}

export function AgentDashboard({
  status,
  globalVoice,
  wsConnected,
  wsStatus,
  workflow,
  resetWorkflow,
  sendMessage,
  onClose,
}: AgentDashboardProps) {
  const activePanel = usePanelStore((s) => s.activePanel);
  const setActivePanel = usePanelStore((s) => s.setActivePanel);
  const isCompact = usePanelStore((s) => s.isCompact);
  const setCompactByViewport = usePanelStore((s) => s.setCompactByViewport);

  const isExecuting = workflow.status === 'executing' || Boolean(status?.orchestrator_state?.includes('EXECUTING'));
  const isPlanning = workflow.status === 'planning' || Boolean(status?.orchestrator_state?.includes('PLANNING'));
  const isWorking = isExecuting || isPlanning;

  useEffect(() => {
    if (typeof window === 'undefined') return;
    setCompactByViewport(window.innerWidth);

    const handleResize = () => setCompactByViewport(window.innerWidth);
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, [setCompactByViewport]);

  const connectionLabel = !wsConnected ? 'OFFLINE' : isWorking ? 'WORKING' : 'READY';
  const connectionTone = !wsConnected ? 'offline' : isWorking ? 'working' : 'ready';

  return (
    <div className="agent-dashboard">
      <div className="agent-dashboard__ambient" />

      <section className="agent-workspace">
        <header className="agent-workspace__header">
          <div className="agent-workspace__header-left">
            <button
              type="button"
              aria-label="Close"
              onClick={onClose}
              className="agent-workspace__close-btn"
            >
              ×
            </button>
            <div className="agent-workspace__brand">
              <span className="agent-workspace__brand-dot" />
              <span className="agent-workspace__brand-text">LAVIS PANEL</span>
            </div>
          </div>

          <div className="agent-workspace__header-right">
            <span className={`agent-workspace__status agent-workspace__status--${connectionTone}`}>
              {connectionLabel}
            </span>
            <span className="agent-workspace__drag-tip">Drag Window</span>
          </div>
        </header>

        <div className="agent-workspace__body">
          <AgentSidebar
            activePanel={activePanel}
            onPanelChange={setActivePanel}
            isConnected={wsConnected}
            isWorking={isWorking}
            compact={isCompact}
          />

          <div className="agent-workspace__content">
            <AgentPanels
              status={status}
              globalVoice={globalVoice}
              wsConnected={wsConnected}
              wsStatus={wsStatus}
              workflow={workflow}
              resetWorkflow={resetWorkflow}
              sendMessage={sendMessage}
              onClose={onClose}
              activePanel={activePanel}
            />
          </div>
        </div>
      </section>
    </div>
  );
}
