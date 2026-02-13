import {
  MessagesSquare,
  Orbit,
  CalendarClock,
  Sparkles,
  Key
} from 'lucide-react';
import './Sidebar.css';

export type PanelType = 'chat' | 'brain' | 'management' | 'scheduler' | 'skills' | 'settings';

interface SidebarProps {
  activePanel: PanelType;
  onPanelChange: (panel: PanelType) => void;
  isConnected: boolean;
  isWorking: boolean;
}

interface NavItem {
  id: PanelType;
  icon: typeof MessagesSquare;
  label: string;
  shortcut?: string;
}

const navItems: NavItem[] = [
  { id: 'chat', label: 'Chat', icon: MessagesSquare, shortcut: '⌘1' },
  { id: 'brain', label: 'Brain', icon: Orbit, shortcut: '⌘2' },
  { id: 'scheduler', label: 'Schedule', icon: CalendarClock, shortcut: '⌘3' },
  { id: 'skills', label: 'Skills', icon: Sparkles, shortcut: '⌘4' },
  { id: 'settings', label: 'Settings', icon: Key, shortcut: '⌘5' },
  // Studio / Management 面板当前隐藏，避免与 Skills / Scheduler 重复
  // { id: 'management', label: 'Studio', icon: Settings2, shortcut: '⌘,' },
];

export function Sidebar({
  activePanel,
  onPanelChange,
  isConnected,
  isWorking,
}: SidebarProps) {
  return (
    <aside className="sidebar">
      {/* Status indicator */}
      <div className="sidebar__status">
        <div
          className={`sidebar__status-dot ${isConnected ? 'sidebar__status-dot--connected' : ''} ${isWorking ? 'sidebar__status-dot--working' : ''}`}
          title={isConnected ? (isWorking ? 'Lavis is thinking' : 'Connected to Lavis') : 'Disconnected'}
        />
        <div className="sidebar__status-label">
          {isConnected ? (isWorking ? 'WORKING' : 'READY') : 'OFFLINE'}
        </div>
      </div>

      {/* Main navigation */}
      <nav className="sidebar__nav">
        {navItems.map((item) => {
          const Icon = item.icon;
          const isActive = activePanel === item.id;
          return (
            <button
              key={item.id}
              className={`sidebar__nav-item ${isActive ? 'sidebar__nav-item--active' : ''}`}
              onClick={() => onPanelChange(item.id)}
              title={`${item.label}${item.shortcut ? ` (${item.shortcut})` : ''}`}
            >
              <Icon className="sidebar__nav-icon" size={26} strokeWidth={1.6} />
              <span className="sidebar__nav-label">{item.label}</span>
            </button>
          );
        })}
      </nav>
    </aside>
  );
}
