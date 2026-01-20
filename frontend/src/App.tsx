import { useState, useEffect, useCallback } from 'react';
import { Capsule } from './components/Capsule';
import { ChatPanel } from './components/ChatPanel';
import { agentApi } from './api/agentApi';
import { useGlobalVoice } from './hooks/useGlobalVoice';
import type { AgentStatus } from './types/agent';
import './App.css';

type ViewMode = 'capsule' | 'chat';

export default function App() {
  const [viewMode, setViewMode] = useState<ViewMode>('capsule');
  const [status, setStatus] = useState<AgentStatus | null>(null);
  const [isStarted, setIsStarted] = useState(false);

  // ====================================
  // 全局语音大脑 (Global Voice Brain)
  // 无论 viewMode 如何变化，唤醒词监听始终运行
  // 必须在用户点击开始后才初始化音频功能（浏览器安全策略）
  // ====================================
  const globalVoice = useGlobalVoice(isStarted);

  // Debug: Check on mount
  useEffect(() => {
    console.log('🚀 App mounted - Global Voice Brain initialized');
    console.log(`   Wake word listening: ${globalVoice.isWakeWordListening ? '✅ Active' : '❌ Inactive'}`);
  }, [globalVoice.isWakeWordListening]);

  // Start heartbeat on mount
  useEffect(() => {
    agentApi.startHeartbeat((newStatus) => {
      setStatus(newStatus);
    });

    return () => {
      agentApi.stopHeartbeat();
    };
  }, []);

  // Handle capsule click - switch to chat mode
  const handleCapsuleClick = useCallback(() => {
    console.log('Capsule clicked, switching to chat mode');
    setViewMode('chat');
  }, []);

  // Handle chat close - switch back to capsule mode
  const handleChatClose = useCallback(() => {
    console.log('Chat closed, switching to capsule mode');
    setViewMode('capsule');
  }, []);

  // Handle wake word detection - switch to chat mode
  useEffect(() => {
    if (globalVoice.wakeWordDetected) {
      console.log('Wake word detected, switching to chat mode');
      setViewMode('chat');
    }
  }, [globalVoice.wakeWordDetected]);

  // Listen for auto-record event (triggered by mic button on start overlay)
  useEffect(() => {
    const handleAutoRecord = () => {
      console.log('🎤 Auto-record triggered, starting recording...');
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
  }, [globalVoice]);

  // Handle mic button click - initialize audio context AND start recording immediately
  const handleMicStart = useCallback(() => {
    console.log('🎤 User clicked mic button, initializing and starting recording...');
    setIsStarted(true);
    // 标记需要在初始化完成后自动开始录音
    // 由于 globalVoice 还未初始化，我们使用 setTimeout 确保状态更新后再触发录音
    setTimeout(() => {
      // globalVoice.startRecording 会在 useGlobalVoice 初始化后可用
      // 这里通过设置一个标志来触发录音
      window.dispatchEvent(new CustomEvent('lavis-auto-record'));
    }, 100);
  }, []);

  // Show start overlay until user clicks to start
  if (!isStarted) {
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
          <p className="start-overlay__subtitle">您的本地 AI 智能助手</p>

          {/* 配置警告 */}
          {!hasPicoKey && (
            <div className="start-overlay__warning">
              <p>⚠️ 缺少 Picovoice Access Key</p>
              <p className="start-overlay__warning-detail">
                语音唤醒功能需要配置 Picovoice Access Key
              </p>
              <p className="start-overlay__warning-detail">
                请在 <code>.env.local</code> 文件中添加:
              </p>
              <pre className="start-overlay__code">
                VITE_PICOVOICE_KEY=your_access_key_here
              </pre>
              <p className="start-overlay__warning-detail">
                <a href="https://console.picovoice.ai/" target="_blank" rel="noopener noreferrer">
                  前往 Picovoice Console 获取免费 Access Key
                </a>
              </p>
            </div>
          )}

          {hasPicoKey && !hasWakeWordPath && (
            <div className="start-overlay__warning">
              <p>⚠️ 缺少唤醒词模型</p>
              <p className="start-overlay__warning-detail">
                请在 <code>.env.local</code> 中配置唤醒词模型路径或 Base64 编码:
              </p>
              <pre className="start-overlay__code">
                VITE_WAKE_WORD_PATH=/hi-lavis.ppn
                # 或
                VITE_WAKE_WORD_BASE64=&lt;base64 encoded .ppn file&gt;
              </pre>
            </div>
          )}

          {/* 麦克风按钮 - 点击即开始并录音 */}
          <button
            className="start-overlay__mic-button"
            onClick={handleMicStart}
            disabled={!hasPicoKey}
            title="点击开始对话"
          >
            <svg className="start-overlay__mic-icon" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
              <path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3z"/>
              <path d="M17 11c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z"/>
            </svg>
          </button>
          
          <p className="start-overlay__mic-hint">
            {hasPicoKey ? '点击麦克风开始对话' : '请先完成配置'}
          </p>
          
          <p className="start-overlay__hint">
            {hasPicoKey && hasWakeWordPath
              ? '点击后将自动进入语音对话模式'
              : '需要麦克风权限以支持语音唤醒与对话'
            }
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="app">
      <div className={`stage stage--${viewMode}`}>
        {viewMode === 'capsule' && (
          <Capsule
            status={status}
            onClick={handleCapsuleClick}
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
