import { MessagesSquare, Orbit, CalendarClock, Sparkles, Key } from 'lucide-react';

export type AgentPanelType = 'chat' | 'brain' | 'scheduler' | 'skills' | 'settings';

interface AgentSidebarProps {
  activePanel: AgentPanelType;
  onPanelChange: (panel: AgentPanelType) => void;
  isConnected: boolean;
  isWorking: boolean;
}

const navItems: { id: AgentPanelType; icon: typeof MessagesSquare; label: string }[] = [
  { id: 'chat', label: 'Chat', icon: MessagesSquare },
  { id: 'brain', label: 'Brain', icon: Orbit },
  { id: 'scheduler', label: 'Scheduler', icon: CalendarClock },
  { id: 'skills', label: 'Skills', icon: Sparkles },
  { id: 'settings', label: 'Settings', icon: Key },
];

export function AgentSidebar({ activePanel, onPanelChange, isConnected, isWorking }: AgentSidebarProps) {
  const statusText = isConnected ? (isWorking ? 'WORKING' : 'READY') : 'OFFLINE';
  const statusColor = !isConnected
    ? 'bg-slate-500/80'
    : isWorking
      ? 'bg-amber-300'
      : 'bg-emerald-300';

  return (
    <aside
      className={[
        'relative flex h-full w-[82px] flex-shrink-0 flex-col items-stretch justify-between',
        'border-r border-slate-800 bg-slate-950',
        // 整体下移，避免与左上角关闭按钮重叠
        'px-3 pt-12 pb-5',
      ].join(' ')}
    >
      <div className="flex flex-1 flex-col items-center justify-between py-1">
        <div className="flex flex-col items-center gap-4">
          <div className="relative">
            <div className="relative inline-flex items-center gap-1.5 rounded-full border border-slate-700 bg-slate-900 px-3 py-1">
              <span className={`h-1.5 w-1.5 rounded-full ${statusColor}`} />
              <span className="bg-gradient-to-r from-slate-200 via-slate-50 to-slate-300 bg-clip-text text-[9px] font-semibold uppercase tracking-[0.25em] text-transparent">
                {statusText}
              </span>
            </div>
          </div>

          {/* 导航菜单：图标托盘 + 悬浮标签（整体稍微再下移一点） */}
          <nav className="mt-8 flex flex-col items-center gap-2.5">
            {navItems.map((item) => {
              const Icon = item.icon;
              const isActive = activePanel === item.id;
              return (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => onPanelChange(item.id)}
                  className={[
                    'group relative flex h-9 w-9 items-center justify-center rounded-xl',
                    'text-slate-300',
                    'border border-slate-700 bg-slate-900',
                    'transition-all duration-150 ease-out',
                    'hover:bg-slate-800 hover:text-slate-50 hover:border-slate-500',
                    isActive
                      ? 'bg-slate-800 text-slate-50 border-slate-400'
                      : '',
                  ].join(' ')}
                >
                  <Icon className="h-4 w-4" strokeWidth={1.5} />

                  {/* 悬浮标签：不占横向空间，仅在 hover 时显示 */}
                  <span
                    className={[
                      'pointer-events-none absolute left-full ml-3 whitespace-nowrap rounded-md',
                      'bg-slate-900 px-2.5 py-1 text-[11px] font-medium text-slate-50',
                      'border border-slate-700',
                      'opacity-0 -translate-x-1 group-hover:translate-x-0 group-hover:opacity-100',
                      'transition-all duration-150',
                    ].join(' ')}
                  >
                    {item.label}
                  </span>
                </button>
              );
            })}
          </nav>
        </div>

        {/* 底部品牌信息：仅保留 Lavis 徽标，去掉额外文字 */}
        <div className="flex flex-col items-center gap-0.5 text-[10px] text-slate-400">
          <div className="inline-flex items-center gap-1 rounded-full border border-slate-700 bg-slate-900 px-2.5 py-1">
            <span className="h-1 w-1 rounded-full bg-amber-300" />
            <span className="text-[10px] font-semibold tracking-[0.18em] uppercase text-slate-100">
              Lavis
            </span>
          </div>
        </div>
      </div>
    </aside>
  );
}


