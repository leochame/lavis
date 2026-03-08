import type { AgentStatus } from '../../types/agent';
import type { WorkflowState, ConnectionStatus } from '../../hooks/useWebSocket';

interface AgentHeaderProps {
  status: AgentStatus | null;
  wsConnected: boolean;
  wsStatus: ConnectionStatus;
  workflow: WorkflowState;
  onClose: () => void;
}

export function AgentHeader({ status, wsConnected, workflow, onClose }: AgentHeaderProps) {
  // 计算状态文本和颜色
  const getStatusInfo = () => {
    if (!wsConnected) {
      return {
        text: 'OFFLINE',
        color: 'bg-slate-500',
        glow: '',
      };
    }
    if (workflow.status === 'executing' || workflow.status === 'planning') {
      return {
        text: 'WORKING',
        color: 'bg-amber-400',
        glow: '',
      };
    }
    return {
      text: 'READY',
      color: 'bg-emerald-400',
      glow: '',
    };
  };

  const statusInfo = getStatusInfo();

  // 获取模型名称（从 status 或默认值）
  const modelName = status?.model_name || 'agent-backend';

  return (
    <header
      className="flex items-center justify-between gap-3 border-b border-white/5 px-3 py-1.5
                 md:gap-4 md:px-4 md:py-2"
    >
      {/* 左侧：关闭按钮 + 紧凑标题（去掉副标题说明，进一步压缩头部信息量） */}
      <div className="flex min-w-0 items-center gap-3">
        <button
          type="button"
          onClick={onClose}
          className="flex h-6 w-6 items-center justify-center rounded-full border border-slate-700 text-[11px] text-slate-400 transition-colors hover:bg-slate-800 hover:text-slate-50"
          aria-label="Close dashboard"
        >
          ×
        </button>
        <span className="min-w-0 truncate text-[13px] font-medium tracking-tight text-slate-50 md:text-sm">
          Lavis Agent
        </span>
      </div>

      {/* 右侧：仅保留状态胶囊，删除模型标签和胶囊提示，进一步收紧布局 */}
      <div className="flex flex-shrink-0 items-center gap-1.5 md:gap-2">
        <span
          className={[
            'inline-flex items-center gap-1 rounded-full bg-slate-900 px-2.5 py-1',
            'text-[11px] text-slate-300 transition-all duration-200',
          ].join(' ')}
        >
          <span
            className={[
              'h-1.5 w-1.5 rounded-full',
              statusInfo.color,
            ].join(' ')}
          />
          <span className="uppercase tracking-[0.08em]">{statusInfo.text}</span>
        </span>
      </div>
    </header>
  );
}

