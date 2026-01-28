import {
  MessagesSquare,
  Orbit,
  Settings2,
  Camera,
  Mic,
  X,
  CalendarClock,
  Sparkles
} from 'lucide-react';
import './Sidebar.css';

export type PanelType = 'chat' | 'brain' | 'management' | 'scheduler' | 'skills';

interface SidebarProps {
  activePanel: PanelType;
  onPanelChange: (panel: PanelType) => void;
  showVoice: boolean;
  onToggleVoice: () => void;
  showScreenshot: boolean;
  onToggleScreenshot: () => void;
  onClose: () => void;
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
  { id: 'management', label: 'Studio', icon: Settings2, shortcut: '⌘,' },
];

export function Sidebar({
  activePanel,
  onPanelChange,
  showVoice,
  onToggleVoice,
  showScreenshot,
  onToggleScreenshot,
  onClose,
  isConnected,
  isWorking,
}: SidebarProps) {
  return (
    <aside className="sidebar">
      {/* Status indicator */}
      <div className="sidebar__status">
        <div
          className={`sidebar__status-dot ${isConnected ? 'sidebar__status-dot--connected' : ''} ${isWorking ? 'sidebar__status-dot--working' : ''}`}
          title={isConnected ? (isWorking ? 'Working...' : 'Connected') : 'Disconnected'}
        />
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
              <Icon className="sidebar__nav-icon" size={28} strokeWidth={1.75} />
              <span className="sidebar__nav-label">{item.label}</span>
            </button>
          );
        })}
      </nav>

      {/* Utility actions */}
      <div className="sidebar__actions">
        <button
          className={`sidebar__action-btn ${showVoice ? 'sidebar__action-btn--active' : ''}`}
          onClick={onToggleVoice}
          title="Voice input"
        >
          <Mic size={24} strokeWidth={1.75} />
        </button>
        <button
          className={`sidebar__action-btn ${showScreenshot ? 'sidebar__action-btn--active' : ''}`}
          onClick={onToggleScreenshot}
          title="Screen capture"
        >
          <Camera size={24} strokeWidth={1.75} />
        </button>
      </div>

      {/* Close button */}
      <button
        className="sidebar__close"
        onClick={onClose}
        title="Close window"
      >
        <X size={20} strokeWidth={1.75} />
      </button>
    </aside>
  );
}
