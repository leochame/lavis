import { useState, useEffect, useCallback } from 'react';
import { Capsule } from './components/Capsule';
import { ChatPanel } from './components/ChatPanel';
import { agentApi } from './api/agentApi';
import { useGlobalVoice } from './hooks/useGlobalVoice';
import { useWebSocket } from './hooks/useWebSocket';
import type { AgentStatus } from './types/agent';
import { useUIStore } from './store/uiStore';
import { usePlatform } from './platforms/PlatformProvider';
import './App.css';

export default function App() {
  const { platform, isElectron } = usePlatform();
  const appClassName = `app ${isElectron ? 'app--electron' : 'app--web'}`;
  const viewMode = useUIStore((s) => s.viewMode);
  const setViewMode = useUIStore((s) => s.setViewMode);
  const windowState = useUIStore((s) => s.windowState);
  const setWindowState = useUIStore((s) => s.setWindowState);
  const [status, setStatus] = useState<AgentStatus | null>(null);
  // Electron 模式下自动启动，Web 模式需要用户点击
  const [isStarted, setIsStarted] = useState(false);

  // Electron 模式下自动启动
  useEffect(() => {
    if (isElectron && !isStarted) {
      setIsStarted(true);
    }
  }, [isElectron, isStarted]);

  // ====================================
  // 全局语音大脑 (Global Voice Brain)
  // 无论 viewMode 如何变化，唤醒词监听始终运行
  // 必须在用户点击开始后才初始化音频功能（浏览器安全策略）
  // ====================================

  // 先初始化 WebSocket 以获取 sessionId
  const wsUrl = agentApi.getWebSocketUrl();

  // 初始化全局语音（先不传 sessionId，稍后通过 effect 更新）
  const globalVoice = useGlobalVoice(isStarted);

  // 初始化 WebSocket，传入 TTS 回调
  useWebSocket(wsUrl, globalVoice.ttsCallbacks);


  // Start heartbeat on mount
  useEffect(() => {
    agentApi.startHeartbeat((newStatus) => {
      setStatus(newStatus);
    });

    return () => {
      agentApi.stopHeartbeat();
    };
  }, [isElectron, platform, setViewMode]);

  // Handle capsule click - start recording (new behavior per design spec)
  const handleCapsuleClick = useCallback(() => {
    // 单击现在用于开始录音，由 Capsule 组件内部处理
  }, []);

  // Handle capsule double-click - switch to chat mode (new behavior per design spec)
  const handleCapsuleDoubleClick = useCallback(() => {
    setViewMode('chat');
    setWindowState('expanded');
    if (isElectron) {
      platform.resizeWindow('expanded');
      // 也支持旧的 resize-window-full IPC
      if (window.electron?.platform?.resizeWindowFull) {
        window.electron.platform.resizeWindowFull();
      }
    }
  }, [isElectron, platform, setViewMode, setWindowState]);

  // Handle capsule right-click - show context menu
  const handleCapsuleContextMenu = useCallback(() => {
    // 在 Electron 中，右键菜单由主进程处理
    // 这里可以通过 IPC 触发主进程显示菜单
    if (isElectron && window.electron?.ipcRenderer) {
      window.electron.ipcRenderer.sendMessage('show-context-menu', {});
    }
  }, [isElectron]);

  // Handle chat close - switch back to capsule mode
  const handleChatClose = useCallback(() => {
    setViewMode('capsule');
    if (isElectron) {
      platform.resizeWindow('capsule');
    }
  }, [isElectron, platform, setViewMode]);

  // Handle wake word detection - set window state to Listening
  useEffect(() => {
    if (globalVoice.wakeWordDetected) {
      setWindowState('listening');
      if (isElectron) {
        // 发送 resize-window-mini IPC
        if (window.electron?.platform?.resizeWindowMini) {
          window.electron.platform.resizeWindowMini();
        } else {
          // 降级到使用 resizeWindow
          platform.resizeWindow('listening');
        }
      }
    }
  }, [globalVoice.wakeWordDetected, isElectron, platform, setWindowState]);

  // Auto-collapse to capsule when window state becomes idle or listening
  // This ensures that when the window shrinks (e.g., due to timeout), it returns to capsule mode
  // Only collapse if we're currently in chat mode and window state is not expanded
  useEffect(() => {
    // Only auto-collapse if we're in chat mode but window is not expanded
    // This handles the case where window auto-shrinks due to timeout or inactivity
    // When window shrinks to idle or listening state, we should show capsule instead of simplified chat panel
    if (viewMode === 'chat' && (windowState === 'idle' || windowState === 'listening')) {
      setViewMode('capsule');
      if (isElectron) {
        platform.resizeWindow('capsule');
      }
    }
  }, [windowState, viewMode, isElectron, platform, setViewMode]);

  // 窗口模式变化时同步 Electron 物理窗口
  useEffect(() => {
    if (isElectron) {
      platform.resizeWindow(viewMode);
      // 注意：不要在这里设置 setIgnoreMouseEvents
      // 透明区域穿透应该由 CSS 和窗口配置处理，而不是全局禁用鼠标事件
      // 否则会导致拖拽和点击都无法工作
    }
  }, [isElectron, platform, viewMode]);

  // Listen for auto-record event (triggered by mic button on start overlay)
  useEffect(() => {
    const handleAutoRecord = () => {
      // 切换到 chat 模式并开始录音
      setViewMode('chat');
      // 延迟一点等待 globalVoice 初始化完成
      setTimeout(() => {
        if (globalVoice.startRecording) {
          globalVoice.startRecording();
        }
      }, 500);
    };

    window.addEventListener('lavis-auto-record', handleAutoRecord);
    return () => {
      window.removeEventListener('lavis-auto-record', handleAutoRecord);
    };
  }, [globalVoice, setViewMode]);

  // Handle mic button click - initialize audio context AND start recording immediately
  const handleMicStart = useCallback(() => {
    setIsStarted(true);
    // 标记需要在初始化完成后自动开始录音
    // 由于 globalVoice 还未初始化，我们使用 setTimeout 确保状态更新后再触发录音
    setTimeout(() => {
      // globalVoice.startRecording 会在 useGlobalVoice 初始化后可用
      // 这里通过设置一个标志来触发录音
      window.dispatchEvent(new CustomEvent('lavis-auto-record'));
    }, 100);
  }, []);

  // Show start overlay until user clicks to start (Web mode only)
  // Electron 模式下跳过启动页，直接显示胶囊
  if (!isStarted && !isElectron) {
    // 检查是否缺少 Picovoice 配置
    const hasPicoKey = !!import.meta.env.VITE_PICOVOICE_KEY;
    const hasWakeWordPath = !!import.meta.env.VITE_WAKE_WORD_PATH || !!import.meta.env.VITE_WAKE_WORD_BASE64;

    return (
      <div className="start-overlay">
        <div className="start-overlay__content">
          <div className="start-overlay__capsule">
            <div className="capsule capsule--idle capsule--breathing">
              <div className="capsule__core"></div>
              <div className="capsule__glow"></div>
            </div>
          </div>
          <h1>Lavis AI</h1>
          <p className="start-overlay__subtitle">Your Local AI Assistant</p>

          {/* Configuration warnings */}
          {!hasPicoKey && (
            <div className="start-overlay__warning">
              <p>Missing Picovoice Access Key</p>
              <p className="start-overlay__warning-detail">
                Voice wake word feature requires Picovoice Access Key
              </p>
              <p className="start-overlay__warning-detail">
                Please add to <code>.env.local</code> file:
              </p>
              <pre className="start-overlay__code">
                VITE_PICOVOICE_KEY=your_access_key_here
              </pre>
              <p className="start-overlay__warning-detail">
                <a href="https://console.picovoice.ai/" target="_blank" rel="noopener noreferrer">
                  Visit Picovoice Console to get a free Access Key
                </a>
              </p>
            </div>
          )}

          {hasPicoKey && !hasWakeWordPath && (
            <div className="start-overlay__warning">
              <p>Missing Wake Word Model</p>
              <p className="start-overlay__warning-detail">
                Please configure wake word model path or Base64 encoding in <code>.env.local</code>:
              </p>
              <pre className="start-overlay__code">
                VITE_WAKE_WORD_PATH=/hi-lavis.ppn
                # or
                VITE_WAKE_WORD_BASE64=&lt;base64 encoded .ppn file&gt;
              </pre>
            </div>
          )}

          {/* Microphone button - click to start and record */}
          <button
            className="start-overlay__mic-button"
            onClick={handleMicStart}
            title="Click to start conversation"
          >
            <svg className="start-overlay__mic-icon" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
              <path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3z"/>
              <path d="M17 11c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z"/>
            </svg>
          </button>
          
          <p className="start-overlay__mic-hint">
            {hasPicoKey ? 'Click microphone to start conversation' : 'Click microphone to start conversation (wake word not configured, voice wake-up unavailable)'}
          </p>
          
          <p className="start-overlay__hint">
            {hasPicoKey && hasWakeWordPath
              ? 'Will automatically enter voice conversation mode after clicking'
              : 'Manual voice conversation still available without Picovoice wake word (click microphone to start)'
            }
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className={appClassName}>
      <div className={`stage stage--${viewMode}`}>
        {viewMode === 'capsule' && (
          <Capsule
            status={status}
            onClick={handleCapsuleClick}
            onDoubleClick={handleCapsuleDoubleClick}
            onContextMenu={handleCapsuleContextMenu}
            voiceState={globalVoice.voiceState}
            isWakeWordListening={globalVoice.isWakeWordListening}
            isRecorderReady={globalVoice.isRecorderReady}
            onStartRecording={globalVoice.startRecording}
          />
        )}
        {viewMode === 'chat' && (
          <ChatPanel
            onClose={handleChatClose}
            status={status}
            globalVoice={globalVoice}
          />
        )}
      </div>
    </div>
  );
}
