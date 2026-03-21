import { useState, useEffect } from 'react';
import { Capsule } from './components/Capsule';
import { AgentDashboard } from './components/dashboard/AgentDashboard';
import { agentApi } from './api/agentApi';
import { useGlobalVoice } from './hooks/useGlobalVoice';
import { useWebSocket } from './hooks/useWebSocket';
import type { AgentStatus } from './types/agent';
import { useUIStore } from './store/uiStore';
import { useSettingsStore } from './store/settingsStore';
import { usePlatform } from './platforms/PlatformProvider';
import { useFirstLaunchCheck } from './app/hooks/useFirstLaunchCheck';
import { useElectronWindowSync } from './app/hooks/useElectronWindowSync';
import { useElectronIpcHandlers } from './app/hooks/useElectronIpcHandlers';
import { useAutoRecordListener } from './app/hooks/useAutoRecordListener';
import { useAppInteractions } from './app/hooks/useAppInteractions';
import { StartOverlay } from './app/components/StartOverlay';
import { useAppWorkingState } from './app/selectors/useAppWorkingState';
import './App.css';

export default function App() {
  const { platform, isElectron } = usePlatform();
  const appClassName = `app ${isElectron ? 'app--electron' : 'app--web'}`;
  const viewMode = useUIStore((s) => s.viewMode);
  const setViewMode = useUIStore((s) => s.setViewMode);
  const setWindowState = useUIStore((s) => s.setWindowState);
  const checkStatus = useSettingsStore((s) => s.checkStatus);
  const [status, setStatus] = useState<AgentStatus | null>(null);
  // Electron 模式下自动启动，Web 模式需要用户点击
  const [isStarted, setIsStarted] = useState(false);

  // Load backend .env/config status on mount
  useEffect(() => {
    checkStatus();
  }, [checkStatus]);

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
  // 由 App 统一管理 WebSocket 连接，并将状态下发给子组件，避免重复连接
  const {
    connected: wsConnected,
    status: wsStatus,
    workflow: wsWorkflow,
    isTtsGenerating,
    resetWorkflow: wsResetWorkflow,
    sendMessage: wsSendMessage,
  } = useWebSocket(wsUrl, globalVoice.ttsCallbacks);


  // Start heartbeat on mount
  useEffect(() => {
    agentApi.startHeartbeat((newStatus) => {
      setStatus(newStatus);
    });

    return () => {
      agentApi.stopHeartbeat();
    };
    // 心跳与 Electron / 视图无关，只需在挂载/卸载时运行一次
  }, []);

  const {
    handleCapsuleClick,
    handleCapsuleDoubleClick,
    handleCapsuleContextMenu,
    handleChatClose,
    handleMicStart,
  } = useAppInteractions({
    isElectron,
    setViewMode,
    setWindowState,
    setIsStarted,
  });

  useFirstLaunchCheck({
    isElectron,
    checkStatus,
    setViewMode,
    setWindowState,
  });

  // Handle wake word detection - set window state to Listening
  useEffect(() => {
    if (globalVoice.wakeWordDetected) {
      setWindowState('listening');
    }
  }, [globalVoice.wakeWordDetected, setWindowState]);

  // 移除可能导致状态冲突的自动折叠逻辑

  useElectronWindowSync({ isElectron, platform, viewMode });

  useElectronIpcHandlers({
    isElectron,
    handleChatClose,
    setViewMode,
    setWindowState,
  });

  useAutoRecordListener({
    setViewMode,
    startRecording: globalVoice.startRecording,
  });

  const isWorking = useAppWorkingState({
    workflow: wsWorkflow,
    isTtsGenerating,
    status,
  });

  // Show start overlay until user clicks to start (Web mode only)
  // Electron 模式下跳过启动页，直接显示胶囊
  if (!isStarted && !isElectron) {
    return <StartOverlay onMicStart={handleMicStart} />;
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
            wsConnected={wsConnected}
            isWorking={isWorking}
          />
        )}
        {viewMode === 'chat' && (
          <AgentDashboard
            status={status}
            globalVoice={globalVoice}
            wsConnected={wsConnected}
            wsStatus={wsStatus}
            workflow={wsWorkflow}
            resetWorkflow={wsResetWorkflow}
            sendMessage={wsSendMessage}
            onClose={handleChatClose}
          />
        )}
      </div>
    </div>
  );
}
