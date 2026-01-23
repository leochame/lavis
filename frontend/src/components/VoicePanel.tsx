import { useRef, useEffect } from 'react';
import { Mic, Brain, Volume2, AlertTriangle, StopCircle, Lightbulb, Bot, User } from 'lucide-react';
import type { AgentStatus } from '../types/agent';
import type { VoiceState } from '../hooks/useGlobalVoice';
import './VoicePanel.css';

// Re-export VoiceState for backwards compatibility
export type { VoiceState };

/**
 * 语音交互状态配置
 */
const VOICE_STATE_CONFIG: Record<VoiceState, { label: string; className: string; icon: React.ReactNode }> = {
  idle: { label: '待机中', className: 'voice-panel__status--idle', icon: <Mic size={20} /> },
  listening: { label: '聆听中...', className: 'voice-panel__status--recording', icon: <Brain size={20} /> },
  processing: { label: '思考中...', className: 'voice-panel__status--processing', icon: <Brain size={20} /> },
  speaking: { label: '回答中...', className: 'voice-panel__status--playing', icon: <Volume2 size={20} /> },
  awaiting_audio: { label: '等待音频...', className: 'voice-panel__status--processing', icon: <Volume2 size={20} /> },
  error: { label: '出错了', className: 'voice-panel__status--error', icon: <AlertTriangle size={20} /> },
};

interface VoicePanelProps {
  status: AgentStatus | null;
  /** 语音状态 (来自全局语音大脑) */
  voiceState: VoiceState;
  /** 是否正在录音 */
  isRecording: boolean;
  /** 唤醒词是否正在监听 */
  isWakeWordListening: boolean;
  /** 用户语音转文字结果 */
  transcribedText: string;
  /** Agent 回复文本 */
  agentResponse: string;
  /** 错误信息 */
  error: string | null;
  /** 开始录音 */
  onStartRecording: () => void;
  /** 停止录音 */
  onStopRecording: () => void;
}

/**
 * 语音面板组件 (仅 UI)
 * 
 * 重构后的 VoicePanel 只负责 UI 渲染，
 * 所有语音逻辑由 useGlobalVoice Hook 在 App.tsx 中管理。
 * 
 * 优点:
 * 1. 无论面板是否显示，唤醒词监听始终运行
 * 2. 状态由父组件通过 props 传递，更清晰
 * 3. 组件更轻量，职责单一
 */
export function VoicePanel({ 
  voiceState,
  isRecording,
  isWakeWordListening,
  transcribedText,
  agentResponse,
  error,
  onStartRecording,
  onStopRecording,
}: VoicePanelProps) {
  const transcriptEndRef = useRef<HTMLDivElement>(null);

  // 自动滚动到底部
  useEffect(() => {
    transcriptEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [transcribedText, agentResponse]);

  /**
   * 切换录音状态
   */
  const toggleRecording = () => {
    if (isRecording) {
      onStopRecording();
    } else {
      onStartRecording();
    }
  };

  // 获取当前状态配置（添加防御性检查）
  const stateConfig = VOICE_STATE_CONFIG[voiceState] || VOICE_STATE_CONFIG.idle;

  return (
    <div className="voice-panel">
      <div className="voice-panel__header">
        <h3>
          <Mic size={16} style={{ marginRight: '8px' }} />
          语音交互
        </h3>
        <div className="voice-panel__status">
          <span className={`voice-panel__status-badge ${stateConfig.className}`}>
            <span className="voice-panel__status-icon">{stateConfig.icon}</span>
            {stateConfig.label}
          </span>
        </div>
      </div>

      <div className="voice-panel__controls">
        <button
          className={`voice-panel__record-btn ${voiceState === 'listening' ? 'recording' : ''}`}
          onClick={toggleRecording}
          disabled={voiceState === 'processing'}
        >
          {voiceState === 'listening' ? (
            <>
              <StopCircle size={16} style={{ marginRight: '8px' }} />
              点击停止
            </>
          ) : (
            <>
              <Mic size={16} style={{ marginRight: '8px' }} />
              点击说话
            </>
          )}
        </button>

        {/* 唤醒词提示 */}
        {isWakeWordListening && voiceState === 'idle' && (
          <div className="voice-panel__hint">
            <Lightbulb size={14} style={{ marginRight: '6px' }} />
            或说 "你好拉维斯" 唤醒
          </div>
        )}

        {/* 错误提示 */}
        {error && (
          <div className="voice-panel__error">
            {error}
          </div>
        )}
      </div>

      <div className="voice-panel__transcript">
        {/* 用户输入 */}
        <div className="voice-panel__transcript-item voice-panel__transcript-item--user">
          <span className="voice-panel__transcript-label">
            <User size={14} style={{ marginRight: '6px' }} />
            用户
          </span>
          <span className="voice-panel__transcript-text">
            {transcribedText || (voiceState === 'listening' ? '正在聆听...' : '...')}
          </span>
        </div>

        {/* Agent 回复 */}
        {(agentResponse || voiceState === 'processing') && (
          <div className="voice-panel__transcript-item voice-panel__transcript-item--agent">
            <span className="voice-panel__transcript-label">
              <Bot size={14} style={{ marginRight: '6px' }} />
              Lavis
            </span>
            <span className="voice-panel__transcript-text">
              {voiceState === 'processing' ? (
                <span className="voice-panel__thinking">思考中...</span>
              ) : (
                agentResponse
              )}
            </span>
          </div>
        )}

        {/* 音频播放器已由 useGlobalVoice 的 playAgentAudio 处理，这里不再需要 */}

        <div ref={transcriptEndRef} />
      </div>
    </div>
  );
}
