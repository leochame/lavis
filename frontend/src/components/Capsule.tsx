import { useEffect, useCallback, useRef } from 'react';
import type { AgentStatus } from '../types/agent';
import type { VoiceState } from '../hooks/useGlobalVoice';
import { useUIStore } from '../store/uiStore';
import './Capsule.css';

// Provide a minimal typing for process.env in the renderer to avoid TS errors
declare const process: {
  env?: {
    NODE_ENV?: string;
  };
};

/**
 * Capsule 显示状态
 * 结合了 Agent 状态和语音状态
 */
type CapsuleState = 'idle' | 'thinking' | 'executing' | 'error' | 'listening' | 'speaking';

interface CapsuleProps {
  status: AgentStatus | null;
  /** 单击回调 - 用于唤醒/暂停语音 */
  onClick: () => void;
  /** 双击回调 - 用于展开到聊天模式 */
  onDoubleClick?: () => void;
  /** 右键菜单回调 */
  onContextMenu?: () => void;
  /** 语音交互状态 (来自全局语音大脑) */
  voiceState?: VoiceState;
  /** 唤醒词是否正在监听 */
  isWakeWordListening?: boolean;
  /** 录音机是否已准备好 */
  isRecorderReady?: boolean;
  /** 手动开始录音 */
  onStartRecording?: () => void;
  /** WebSocket 连接状态 */
  wsConnected?: boolean;
  /** 后端是否正在工作（执行或规划中） */
  isWorking?: boolean;
}

export function Capsule({
  status,
  onClick,
  onDoubleClick,
  onContextMenu,
  voiceState,
  isWakeWordListening,
  isRecorderReady,
  onStartRecording,
  wsConnected = false,
  isWorking = false,
}: CapsuleProps) {
  // 获取 TTS 播放状态
  const isTtsPlaying = useUIStore((s) => s.isTtsPlaying);

  // 拖拽状态
  const isDraggingRef = useRef(false);
  const hasDraggedRef = useRef(false); // 用于区分点击和拖拽

  // Debug: log state changes
  useEffect(() => {
    console.log('Capsule state:', {
      voiceState: typeof voiceState === 'object' ? JSON.stringify(voiceState) : voiceState,
      isWakeWordListening,
      isRecorderReady,
      isTtsPlaying
    });
  }, [voiceState, isWakeWordListening, isRecorderReady, isTtsPlaying]);

  /**
   * 综合 Agent 状态和语音状态，确定 Capsule 显示状态
   * 优先级：语音状态 > Agent 状态
   */
  const getCapsuleState = useCallback((): CapsuleState => {
    // 语音状态优先
    if (voiceState === 'listening') return 'listening';
    if (voiceState === 'speaking') return 'speaking';
    if (voiceState === 'processing') return 'thinking';
    if (voiceState === 'error') return 'error';

    // Agent 状态
    if (status?.orchestrator_state === 'API_MISSING' || status?.orchestrator_state === 'IPC_ERROR') return 'error';
    if (!status?.available) return 'error';

    const orchestratorState = status.orchestrator_state;
    if (orchestratorState?.includes('EXECUTING')) return 'executing';
    if (orchestratorState?.includes('THINKING') || orchestratorState?.includes('PLANNING')) return 'thinking';

    return 'idle';
  }, [voiceState, status]);

  const capsuleState = getCapsuleState();

  // 呼吸动画：待机模式监听唤醒词时启用
  const isBreathing = capsuleState === 'idle' && isWakeWordListening;

  // TTS 播放时显示声波纹路（覆盖 speaking 状态）
  const showVoiceRings = (capsuleState === 'listening' || capsuleState === 'speaking') || isTtsPlaying;
  
  // 工作状态指示器：WebSocket 连接正常且后端正在工作
  // 注意：当处于 thinking/executing 状态时也应该显示，即使同时有语音状态
  const showWorkingIndicator = wsConnected && isWorking && 
    (capsuleState === 'thinking' || capsuleState === 'executing' || 
     (capsuleState !== 'listening' && capsuleState !== 'speaking' && !isTtsPlaying));
  
  // Debug: log working indicator state
  useEffect(() => {
    const debugInfo = {
      wsConnected: String(wsConnected),
      isWorking: String(isWorking),
      capsuleState: String(capsuleState),
      isTtsPlaying: String(isTtsPlaying),
      showWorkingIndicator: String(showWorkingIndicator),
      statusOrchestrator: status?.orchestrator_state ? JSON.stringify(status.orchestrator_state) : 'null',
      statusAvailable: typeof status?.available === 'boolean' ? String(status.available) : 'null',
      voiceState: voiceState ? JSON.stringify(voiceState) : 'null'
    };
    console.log('🔍 Capsule working indicator debug:', debugInfo);
    console.log('   → showWorkingIndicator =', showWorkingIndicator, 
                '(wsConnected:', wsConnected, '&& isWorking:', isWorking, 
                '&& capsuleState in [thinking, executing] or not listening/speaking)');
  }, [wsConnected, isWorking, capsuleState, isTtsPlaying, showWorkingIndicator, status, voiceState]);

  /**
   * 拖拽处理 - 使用 IPC 实现丝滑拖拽
   */
  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    // 只响应左键
    if (e.button !== 0) return;

    // 如果点击的是核心区域，不启动拖拽
    if ((e.target as HTMLElement).classList.contains('capsule__core')) {
      return;
    }

    e.preventDefault();
    isDraggingRef.current = true;
    hasDraggedRef.current = false;

    // 使用屏幕坐标
    const startX = e.screenX;
    const startY = e.screenY;

    // 通知主进程开始拖拽
    window.electron?.platform?.dragStart?.(startX, startY);

    const handleMouseMove = (moveEvent: MouseEvent) => {
      if (!isDraggingRef.current) return;

      hasDraggedRef.current = true;

      // 使用屏幕坐标
      window.electron?.platform?.dragMove?.(moveEvent.screenX, moveEvent.screenY);
    };

    const handleMouseUp = () => {
      if (isDraggingRef.current) {
        isDraggingRef.current = false;
        window.electron?.platform?.dragEnd?.();
      }

      document.removeEventListener('mousemove', handleMouseMove);
      document.removeEventListener('mouseup', handleMouseUp);
    };

    document.addEventListener('mousemove', handleMouseMove);
    document.addEventListener('mouseup', handleMouseUp);
  }, []);

  /**
   * 单击处理 - 根据设计规范：唤醒/暂停语音聆听
   * 注意：点击事件绑定在核心区域 (capsule__core)
   */
  const handleCoreClick = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();

    // 如果刚刚拖拽过，不触发点击
    if (hasDraggedRef.current) {
      hasDraggedRef.current = false;
      return;
    }

    console.log('Capsule core clicked, state:', capsuleState);

    // 如果在待机状态且有录音功能，开始录音
    if (capsuleState === 'idle' && onStartRecording) {
      onStartRecording();
    } else {
      onClick();
    }
  }, [capsuleState, onClick, onStartRecording]);

  /**
   * 双击处理 - 根据设计规范：展开到聊天模式
   */
  const handleDoubleClick = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    console.log('Capsule double-clicked, expanding to chat mode');
    onDoubleClick?.();
  }, [onDoubleClick]);

  /**
   * 右键菜单处理 - 呼出系统菜单
   */
  const handleContextMenu = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    console.log('Capsule right-clicked, showing context menu');
    onContextMenu?.();
  }, [onContextMenu]);

  // Generate tooltip text based on state
  const getTooltip = useCallback((): string => {
    switch (capsuleState) {
      case 'listening':
        return isRecorderReady ? 'Listening... (Double-click to expand)' : 'Preparing to record...';
      case 'speaking':
        return 'Speaking... (Double-click to expand)';
      case 'thinking':
        return 'Thinking... (Double-click to expand)';
      case 'executing':
        return 'Executing... (Double-click to expand)';
      case 'error':
        return 'Connection error (Double-click to expand)';
      default:
        if (isWakeWordListening) {
          return isRecorderReady
            ? 'Say "Hi Lavis" or click to start / Double-click to expand / Right-click for menu'
            : 'Preparing...';
        }
        return 'Click to start conversation / Double-click to expand / Right-click for menu';
    }
  }, [capsuleState, isWakeWordListening, isRecorderReady]);

  return (
    <div
      className={`capsule capsule--${capsuleState} ${isBreathing ? 'capsule--breathing' : ''}`}
      onMouseDown={handleMouseDown}
      onDoubleClick={handleDoubleClick}
      onContextMenu={handleContextMenu}
      title={getTooltip()}
    >
      {/* 能量芯 - 点击区域 */}
      <div className="capsule__core" onClick={handleCoreClick} />

      {/* 呼吸光晕 */}
      <div className="capsule__glow" />

      {/* 语音波纹效果 - 在聆听/说话/TTS播放时显示 */}
      {showVoiceRings && (
        <div className="capsule__voice-rings">
          <div className="capsule__voice-ring" />
          <div className="capsule__voice-ring" />
          <div className="capsule__voice-ring" />
        </div>
      )}
      
      {/* 工作状态指示器 - 青烟和波纹效果 */}
      {showWorkingIndicator && (
        <>
          <div className="capsule__working-indicator">
            <div className="capsule__smoke-ring capsule__smoke-ring--1"></div>
            <div className="capsule__smoke-ring capsule__smoke-ring--2"></div>
            <div className="capsule__smoke-ring capsule__smoke-ring--3"></div>
            <div className="capsule__ripple capsule__ripple--1"></div>
            <div className="capsule__ripple capsule__ripple--2"></div>
          </div>
          {/* Debug: 添加一个可见的测试标记 */}
          {process.env?.NODE_ENV === 'development' && (
            <div style={{
              position: 'absolute',
              top: '-30px',
              left: '50%',
              transform: 'translateX(-50%)',
              color: '#00d4ff',
              fontSize: '10px',
              zIndex: 10000,
              pointerEvents: 'none',
              whiteSpace: 'nowrap'
            }}>
              WORKING
            </div>
          )}
        </>
      )}
    </div>
  );
}
