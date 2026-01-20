import { useEffect } from 'react';
import type { AgentStatus } from '../types/agent';
import type { VoiceState } from '../hooks/useGlobalVoice';
import './Capsule.css';

/**
 * Capsule 显示状态
 * 结合了 Agent 状态和语音状态
 */
type CapsuleState = 'idle' | 'thinking' | 'executing' | 'error' | 'listening' | 'speaking';

interface CapsuleProps {
  status: AgentStatus | null;
  onClick: () => void;
  /** 语音交互状态 (来自全局语音大脑) */
  voiceState?: VoiceState;
  /** 唤醒词是否正在监听 */
  isWakeWordListening?: boolean;
  /** 录音机是否已准备好（用于 UI 提示） */
  isRecorderReady?: boolean;
  /** 手动开始录音 (用于长按触发) */
  onStartRecording?: () => void;
}

export function Capsule({ status, onClick, voiceState, isWakeWordListening, isRecorderReady, onStartRecording }: CapsuleProps) {
  // Debug: log when component mounts
  useEffect(() => {
    console.log('Capsule component mounted');
    console.log(`   Voice state: ${voiceState}`);
    console.log(`   Wake word listening: ${isWakeWordListening ? '✅' : '❌'}`);
    console.log(`   Recorder ready: ${isRecorderReady ? '✅' : '❌'}`);
  }, [voiceState, isWakeWordListening, isRecorderReady]);

  /**
   * 综合 Agent 状态和语音状态，确定 Capsule 显示状态
   * 优先级：语音状态 > Agent 状态
   */
  const getCapsuleState = (): CapsuleState => {
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
  };

  const capsuleState = getCapsuleState();

  // 呼吸动画：待机模式监听唤醒词时蓝色呼吸，思考/执行/聆听/说话时触发
  const isBreathing =
    capsuleState === 'idle' && isWakeWordListening ||
    ['thinking', 'executing', 'listening', 'speaking'].includes(capsuleState);

  const handleClick = (e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    console.log('Capsule clicked, current state:', capsuleState);
    onClick();
  };

  /**
   * 双击触发录音 (备用方案，当唤醒词不可用时)
   */
  const handleDoubleClick = (e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (capsuleState === 'idle' && onStartRecording) {
      console.log('Capsule double-clicked, starting recording...');
      onStartRecording();
    }
  };

  // 根据状态生成提示文字
  const getTooltip = (): string => {
    switch (capsuleState) {
      case 'listening': return isRecorderReady ? '正在聆听...' : '准备录音...';
      case 'speaking': return '正在回答...';
      case 'thinking': return '思考中...';
      case 'executing': return '执行中...';
      case 'error': return '连接错误';
      default:
        return isWakeWordListening
          ? (isRecorderReady
              ? '点击打开 / 说 "Hi Lavis" / 双击录音'
              : '正在准备录音机...')
          : '点击打开 / 双击录音';
    }
  };

  return (
    <div
      className={`capsule capsule--${capsuleState} ${isBreathing ? 'capsule--breathing' : ''}`}
      onClick={handleClick}
      onDoubleClick={handleDoubleClick}
      title={getTooltip()}
      style={{ pointerEvents: 'auto' }}
    >
      <div className="capsule__core"></div>
      <div className="capsule__glow"></div>
      
      {/* 唤醒词监听指示器 */}
      {isWakeWordListening && capsuleState === 'idle' && (
        <div className="capsule__wake-indicator" title='唤醒词监听中: "Hi Lavis"'>
          <div className="capsule__wake-pulse"></div>
        </div>
      )}
      
      {/* 语音波纹效果 */}
      {(capsuleState === 'listening' || capsuleState === 'speaking') && (
        <div className="capsule__voice-rings">
          <div className="capsule__voice-ring"></div>
          <div className="capsule__voice-ring"></div>
          <div className="capsule__voice-ring"></div>
        </div>
      )}
    </div>
  );
}
