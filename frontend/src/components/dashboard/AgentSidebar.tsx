import { MessagesSquare, Orbit, Sparkles, Key, Clock3 } from 'lucide-react';
import type { AgentPanelType } from '../../types/panel';
import './AgentSidebar.css';

interface AgentSidebarProps {
  activePanel: AgentPanelType;
  onPanelChange: (panel: AgentPanelType) => void;
  isConnected: boolean;
  isWorking: boolean;
  compact?: boolean;
}

const navItems: { id: AgentPanelType; icon: typeof MessagesSquare; label: string }[] = [
  { id: 'chat', label: 'Chat', icon: MessagesSquare },
  { id: 'brain', label: 'Brain', icon: Orbit },
  { id: 'skills', label: 'Skills', icon: Sparkles },
  { id: 'scheduler', label: 'Scheduler', icon: Clock3 },
  { id: 'settings', label: 'Settings', icon: Key },
];

export function AgentSidebar({
  activePanel,
  onPanelChange,
  isConnected,
  isWorking,
  compact = false,
}: AgentSidebarProps) {
  const statusText = isConnected ? (isWorking ? 'WORKING' : 'READY') : 'OFFLINE';
  const statusTone = !isConnected ? 'offline' : isWorking ? 'working' : 'ready';
  const rootClassName = compact ? 'agent-sidebar agent-sidebar--compact' : 'agent-sidebar';

  return (
    <aside className={rootClassName}>
      <div className="agent-sidebar__head">
        <span className="agent-sidebar__brand">LVS</span>
        <div className={`agent-sidebar__status agent-sidebar__status--${statusTone}`}>
          <span className="agent-sidebar__status-dot" />
          {!compact && <span>{statusText}</span>}
        </div>
      </div>

      <nav className="agent-sidebar__nav">
        {navItems.map((item) => {
          const Icon = item.icon;
          const isActive = activePanel === item.id;
          return (
            <button
              key={item.id}
              type="button"
              onClick={() => onPanelChange(item.id)}
              className={`agent-sidebar__item ${isActive ? 'agent-sidebar__item--active' : ''}`}
              title={item.label}
            >
              <Icon className="agent-sidebar__item-icon" strokeWidth={1.6} />
              {!compact && <span className="agent-sidebar__item-label">{item.label}</span>}
            </button>
          );
        })}
      </nav>

      <div className="agent-sidebar__foot">
        <span className="agent-sidebar__dock-dot" />
        {!compact && <span>Panel Mode</span>}
      </div>
    </aside>
  );
}
