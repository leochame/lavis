import type { ReactNode } from 'react';
import type { AgentStatus } from '../../types/agent';
import type { UseGlobalVoiceReturn } from '../../hooks/useGlobalVoice';
import type { WorkflowState, ConnectionStatus } from '../../hooks/useWebSocket';
import { ChatPanel } from '../ChatPanel';
import { BrainPanel } from '../BrainPanel';
import { SkillsPanel } from '../SkillsPanel';
import { SchedulerPanel } from '../SchedulerPanel';
import { SettingsPanel } from '../SettingsPanel';
import { agentApi } from '../../api/agentApi';
import type { AgentPanelType } from '../../types/panel';

interface AgentPanelsProps {
  status: AgentStatus | null;
  globalVoice: UseGlobalVoiceReturn;
  wsConnected: boolean;
  wsStatus: ConnectionStatus;
  workflow: WorkflowState;
  resetWorkflow: () => void;
  sendMessage: (type: string, data?: Record<string, unknown>) => void;
  onClose: () => void;
  activePanel: AgentPanelType;
}

export function AgentPanels({
  status,
  globalVoice,
  wsConnected,
  wsStatus,
  workflow,
  resetWorkflow,
  sendMessage,
  onClose,
  activePanel,
}: AgentPanelsProps) {
  const handleEmergencyStop = async () => {
    try {
      await agentApi.stop();
    } catch (error) {
      console.error('Failed to stop:', error);
    }
  };

  const renderMainPanel = (): ReactNode => {
    switch (activePanel) {
      case 'chat':
        return (
          // 在 Dashboard 内直接使用 ChatPanel 自带的底部输入栏，避免“对话栏丢失”
          <ChatPanel
            onClose={onClose}
            status={status}
            globalVoice={globalVoice}
            wsConnected={wsConnected}
            wsStatus={wsStatus}
            workflow={workflow}
            resetWorkflow={resetWorkflow}
            sendMessage={sendMessage}
            mode="chat-only"
          />
        );
      case 'brain':
        return (
          <BrainPanel
            workflow={workflow}
            connectionStatus={wsStatus}
            onStop={handleEmergencyStop}
            className="brain-panel--embedded"
            showHeader={false}
          />
        );
      case 'skills':
        return (
          <SkillsPanel className="skills-panel--embedded" showHeader={false} />
        );
      case 'scheduler':
        return (
          <SchedulerPanel className="scheduler-panel--embedded" showHeader={false} />
        );
      case 'settings':
        return (
          <SettingsPanel />
        );
      default:
        return null;
    }
  };

  return (
    <main className={`agent-panels agent-panels--${activePanel}`}>
      <div className="agent-panels__content">
        {renderMainPanel()}
      </div>
    </main>
  );
}
