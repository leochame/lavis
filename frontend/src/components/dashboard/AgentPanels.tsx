import type { ReactNode } from 'react';
import { useEffect } from 'react';
import type { AgentStatus } from '../../types/agent';
import type { UseGlobalVoiceReturn } from '../../hooks/useGlobalVoice';
import type { WorkflowState, ConnectionStatus } from '../../hooks/useWebSocket';
import { ChatPanel } from '../ChatPanel';
import { BrainPanel } from '../BrainPanel';
import { SchedulerPanel } from '../SchedulerPanel';
import { SkillsPanel } from '../SkillsPanel';
import { SettingsPanel } from '../SettingsPanel';
import { agentApi } from '../../api/agentApi';
import type { AgentPanelType } from './AgentSidebar';

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
  onPanelChange: (panel: AgentPanelType) => void;
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
  onPanelChange,
}: AgentPanelsProps) {
  const handleEmergencyStop = async () => {
    try {
      await agentApi.stop();
    } catch (error) {
      // eslint-disable-next-line no-console
      console.error('Failed to stop:', error);
    }
  };

  // 当 workflow 进入规划/执行阶段时，仅在用户未主动选择其他面板时才自动切到 Brain 面板
  useEffect(() => {
    if (
      activePanel === 'chat' &&
      (workflow.status === 'executing' || workflow.status === 'planning') &&
      workflow.steps.length > 0
    ) {
      // 延迟切换，给用户时间看到执行开始
      const timer = setTimeout(() => {
        onPanelChange('brain');
      }, 1000);
      return () => clearTimeout(timer);
    }
  }, [activePanel, workflow.status, workflow.steps.length, onPanelChange]);

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
      case 'scheduler':
        return (
          <SchedulerPanel className="scheduler-panel--embedded" showHeader={false} />
        );
      case 'skills':
        return (
          <SkillsPanel className="skills-panel--embedded" showHeader={false} />
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
    <main className="relative flex flex-1 flex-col overflow-hidden">
      {/* 右侧内容：移除额外的内边距，让各个 Panel 直接铺满工作区 */}
      <div className="flex h-full w-full flex-1 flex-col overflow-hidden">
        {renderMainPanel()}
      </div>
    </main>
  );
}


