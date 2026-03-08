import type { AgentStatus } from '../../types/agent';
import type { UseGlobalVoiceReturn } from '../../hooks/useGlobalVoice';
import type { WorkflowState, ConnectionStatus } from '../../hooks/useWebSocket';
import { useEffect, useRef, useState } from 'react';
import { AgentSidebar, type AgentPanelType } from './AgentSidebar';
import { AgentPanels } from './AgentPanels';

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
  const [activePanel, setActivePanel] = useState<AgentPanelType>('chat');

  const isExecuting = workflow.status === 'executing' || status?.orchestrator_state?.includes('EXECUTING');
  const isPlanning = workflow.status === 'planning';
  const isWorking = isExecuting || isPlanning;

  // 可拖拽调整的控制台尺寸（宽 / 高）
  const [size, setSize] = useState(() => {
    if (typeof window === 'undefined') {
      // SSR / 预渲染阶段：使用一个相对保守的最小尺寸
      return { width: 900, height: 580 };
    }

    const minWidth = 900;
    const minHeight = 580;
    const maxWidth = Math.min(1180, window.innerWidth - 48);
    const maxHeight = Math.min(760, window.innerHeight - 64);

    // 初始即为“最小窗口尺寸”，用户可以在此基础上自由拉大
    return {
      width: Math.min(maxWidth, minWidth),
      height: Math.min(maxHeight, minHeight),
    };
  });

  // 面板在主视图中的位移（基于居中位置的偏移量，可以随意拖拽移动）
  const [position, setPosition] = useState({ x: 0, y: 0 });

  // 拖拽与缩放的内部状态
  const [isDragging, setIsDragging] = useState(false);
  const [isResizing, setIsResizing] = useState(false);

  const dragRef = useRef<{ startX: number; startY: number; originX: number; originY: number }>({
    startX: 0,
    startY: 0,
    originX: 0,
    originY: 0,
  });

  const resizeRef = useRef<{ startX: number; startY: number; originWidth: number; originHeight: number }>({
    startX: 0,
    startY: 0,
    originWidth: size.width,
    originHeight: size.height,
  });

  // 当浏览器窗口 / Electron 物理窗口尺寸变化时，自适应更新面板尺寸
  // 这样从胶囊切换到 Chat 时，窗口放大后面板也会随之“铺开”，而不是保持胶囊时期的小尺寸
  useEffect(() => {
    if (typeof window === 'undefined') return;

    const updateSizeToWindow = () => {
      const minWidth = 900;
      const minHeight = 580;
      const maxWidth = Math.min(1180, window.innerWidth - 48);
      const maxHeight = Math.min(760, window.innerHeight - 64);

      setSize((prev) => {
        const nextWidth = Math.min(maxWidth, Math.max(prev.width, minWidth));
        const nextHeight = Math.min(maxHeight, Math.max(prev.height, minHeight));

        // 如果没有实际变化，避免多余的状态更新
        if (nextWidth === prev.width && nextHeight === prev.height) {
          return prev;
        }
        return { width: nextWidth, height: nextHeight };
      });
    };

    // 初次挂载以及每次窗口尺寸变化时同步一次
    updateSizeToWindow();
    window.addEventListener('resize', updateSizeToWindow);

    return () => {
      window.removeEventListener('resize', updateSizeToWindow);
    };
  }, []);

  // 全局鼠标事件：处理拖拽移动和右下角缩放
  useEffect(() => {
    if (typeof window === 'undefined') return;
    if (!isDragging && !isResizing) return;

    const handleMouseMove = (event: MouseEvent) => {
      if (isDragging) {
        const dx = event.clientX - dragRef.current.startX;
        const dy = event.clientY - dragRef.current.startY;

        setPosition({
          x: dragRef.current.originX + dx,
          y: dragRef.current.originY + dy,
        });
      } else if (isResizing) {
        const dx = event.clientX - resizeRef.current.startX;
        const dy = event.clientY - resizeRef.current.startY;

        const minWidth = 900;
        const minHeight = 580;
        const maxWidth = Math.min(1180, window.innerWidth - 48);
        const maxHeight = Math.min(760, window.innerHeight - 64);

        setSize({
          width: Math.min(maxWidth, Math.max(minWidth, resizeRef.current.originWidth + dx)),
          height: Math.min(maxHeight, Math.max(minHeight, resizeRef.current.originHeight + dy)),
        });
      }
    };

    const handleMouseUp = () => {
      if (isDragging) {
        setIsDragging(false);
      }
      if (isResizing) {
        setIsResizing(false);
      }
    };

    window.addEventListener('mousemove', handleMouseMove);
    window.addEventListener('mouseup', handleMouseUp);

    return () => {
      window.removeEventListener('mousemove', handleMouseMove);
      window.removeEventListener('mouseup', handleMouseUp);
    };
  }, [isDragging, isResizing]);

  // 顶部拖拽区域：按住即可拖动整个控制台面板
  const handleDragStart = (event: React.MouseEvent<HTMLDivElement>) => {
    if (event.button !== 0) return; // 仅响应左键
    event.preventDefault();

    setIsDragging(true);
    dragRef.current = {
      startX: event.clientX,
      startY: event.clientY,
      originX: position.x,
      originY: position.y,
    };
  };

  // 右下角缩放握柄：按住后可自由拉伸窗口尺寸（受最小 / 最大尺寸约束）
  const handleResizeStart = (event: React.MouseEvent<HTMLDivElement>) => {
    if (event.button !== 0) return;
    event.preventDefault();
    event.stopPropagation();

    setIsResizing(true);
    resizeRef.current = {
      startX: event.clientX,
      startY: event.clientY,
      originWidth: size.width,
      originHeight: size.height,
    };
  };

  return (
    <div className="relative flex h-full w-full items-center justify-center px-3 py-3 text-slate-100">
      {/* 主容器：可拖拽 / 可缩放的浮动控制台面板 */}
      <div
        className={[
          'relative z-50 mx-auto flex flex-col overflow-hidden rounded-2xl',
          // 纯色深色背景：不再使用玻璃模糊、光晕或重阴影
          'bg-slate-950',
        ].join(' ')}
        style={{
          width: size.width,
          height: size.height,
          transform: `translate3d(${position.x}px, ${position.y}px, 0)`,
          transition: isDragging || isResizing ? 'none' : 'transform 120ms ease-out',
        }}
      >
        {/* 顶部“隐形”拖拽区域：不遮挡关闭按钮等 UI，仅用于拖拽移动 */}
        <div
          className="pointer-events-auto absolute inset-x-10 top-0 h-8 cursor-move"
          onMouseDown={handleDragStart}
        />

        {/* 悬浮关闭按钮：调整z-index避免被遮挡 */}
        <div className="pointer-events-none absolute left-7 top-4 z-30">
          <button
            type="button"
            aria-label="Close"
            onClick={onClose}
            className="pointer-events-auto flex h-7 w-7 items-center justify-center rounded-full bg-slate-900/90 text-sm text-slate-100 shadow-sm transition-colors hover:bg-slate-800"
          >
            ×
          </button>
        </div>
        {/* 主体区域：左侧边栏 + 右侧内容（去掉右侧顶部栏，让工作区垂直方向完全铺满） */}
        <div className="relative flex flex-1 overflow-hidden">
          {/* 左侧边栏 */}
          <AgentSidebar
            activePanel={activePanel}
            onPanelChange={setActivePanel}
            isConnected={wsConnected}
            isWorking={isWorking}
          />

          {/* 右侧主内容区 */}
          <div className="relative flex flex-1 flex-col overflow-hidden">
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
              onPanelChange={setActivePanel}
            />
          </div>
        </div>

        {/* 右下角缩放握柄：用于调整窗口大小 */}
        <div
          className="pointer-events-auto absolute bottom-1 right-1 h-3 w-3 cursor-se-resize rounded-sm border border-slate-700 bg-slate-800/80"
          onMouseDown={handleResizeStart}
        />
      </div>
    </div>
  );
}


