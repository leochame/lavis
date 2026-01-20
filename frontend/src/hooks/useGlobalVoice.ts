import { useState, useEffect, useRef, useCallback } from 'react';
import { agentApi } from '../api/agentApi';
import { useVoiceRecorder } from './useVoiceRecorder';
import { useWakeWord } from './useWakeWord';

/**
 * 语音交互状态
 */
export type VoiceState = 'idle' | 'listening' | 'processing' | 'speaking' | 'error';

/**
 * 全局语音 Hook 返回值
 */
export interface UseGlobalVoiceReturn {
  /** 当前语音状态 */
  voiceState: VoiceState;
  /** 唤醒词是否正在监听 */
  isWakeWordListening: boolean;
  /** 是否正在录音 */
  isRecording: boolean;
  /** 录音机是否已准备好（避免唤醒词检测后立即说话被截断） */
  isRecorderReady: boolean;
  /** 用户语音转文字结果 */
  transcribedText: string;
  /** Agent 回复文本 */
  agentResponse: string;
  /** Agent 回复音频 (Base64) */
  agentAudio: string | null;
  /** 错误信息 */
  error: string | null;
  /** 唤醒词是否被检测到（用于切换到聊天模式） */
  wakeWordDetected: boolean;
  /** 手动开始录音 */
  startRecording: () => void;
  /** 手动停止录音 */
  stopRecording: () => void;
  /** 重置状态 */
  reset: () => void;
}

/**
 * 全局 AudioContext 单例
 * 在用户点击开始时创建，复用用于所有音频播放
 * 避免重复创建导致的浏览器限制问题
 */
let globalAudioContext: AudioContext | null = null;

/**
 * 获取或创建全局 AudioContext
 * 必须在用户手势触发后调用
 */
const getAudioContext = (): AudioContext | null => {
  if (!globalAudioContext) {
    try {
      const AudioContextClass = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      globalAudioContext = new AudioContextClass();
      console.log('✅ Global AudioContext created');
    } catch (e) {
      console.warn("AudioContext not supported or failed", e);
    }
  }
  return globalAudioContext;
};

/**
 * 简单的 "Ding" 提示音生成器
 * 使用全局 AudioContext 合成一个清脆的提示音
 */
const playDing = () => {
  try {
    const audioContext = getAudioContext();
    if (!audioContext) return;

    // 如果 AudioContext 处于 suspended 状态，尝试 resume
    if (audioContext.state === 'suspended') {
      audioContext.resume().catch(console.warn);
    }

    const oscillator = audioContext.createOscillator();
    const gainNode = audioContext.createGain();

    oscillator.connect(gainNode);
    gainNode.connect(audioContext.destination);

    // 两个音调叠加，更悦耳
    oscillator.type = 'sine';
    oscillator.frequency.setValueAtTime(880, audioContext.currentTime); // A5
    oscillator.frequency.exponentialRampToValueAtTime(1320, audioContext.currentTime + 0.08); // E6
    oscillator.frequency.exponentialRampToValueAtTime(1760, audioContext.currentTime + 0.15); // A6

    gainNode.gain.setValueAtTime(0.3, audioContext.currentTime);
    gainNode.gain.exponentialRampToValueAtTime(0.01, audioContext.currentTime + 0.2);

    oscillator.start();
    oscillator.stop(audioContext.currentTime + 0.2);
  } catch (e) {
    console.warn("Ding sound playback failed", e);
  }
};

/**
 * 全局语音交互 Hook
 *
 * 核心功能:
 * 1. 唤醒词监听 (始终运行，除非正在处理其他语音任务)
 * 2. 语音录制
 * 3. 语音对话 (STT -> Agent -> TTS)
 * 4. 状态管理
 *
 * 设计原则:
 * - 这个 Hook 应该在 App.tsx 中初始化，确保生命周期最长
 * - 无论 UI 如何切换，唤醒词监听始终存在
 * - 必须在用户点击开始后才初始化音频功能（浏览器安全策略）
 *
 * @param isAppStarted - 用户是否已点击"开始"按钮（激活麦克风和音频上下文）
 */
export function useGlobalVoice(isAppStarted: boolean): UseGlobalVoiceReturn {
  // 核心状态
  const [voiceState, setVoiceState] = useState<VoiceState>('idle');
  const [transcribedText, setTranscribedText] = useState('');
  const [agentResponse, setAgentResponse] = useState('');
  const [agentAudio, setAgentAudio] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [wakeWordDetected, setWakeWordDetected] = useState(false);
  const [isRecorderReady, setIsRecorderReady] = useState(false); // 录音机是否已准备好

  // 音频播放器引用
  const audioRef = useRef<HTMLAudioElement | null>(null);

  // 初始化录音 Hook
  const {
    isRecording,
    isRecordingReady,
    startRecording: recorderStart,
    stopRecording: recorderStop,
    audioBlob,
    error: recorderError,
    isTooShort,
  } = useVoiceRecorder();

  // 追踪录音机是否已准备好
  useEffect(() => {
    if (isRecordingReady) {
      setIsRecorderReady(true);
    }
  }, [isRecordingReady]);

  // 更新状态的便捷函数
  const updateState = useCallback((newState: VoiceState) => {
    console.log(`🎤 Voice state: ${voiceState} -> ${newState}`);
    setVoiceState(newState);
  }, [voiceState]);

  // 处理语音对话
  const handleVoiceChat = useCallback(async (blob: Blob) => {
    updateState('processing');
    setError(null);
    
    try {
      const file = new File([blob], "recording.webm", { type: blob.type });
      console.log('📤 Uploading audio...', { size: blob.size, type: blob.type });
      
      const response = await agentApi.voiceChat(file);
      
      if (response.success) {
        setTranscribedText(response.user_text);
        setAgentResponse(response.agent_text);
        setAgentAudio(response.agent_audio);
        
        // 如果有音频，播放它
        if (response.agent_audio) {
          updateState('speaking');
          playAgentAudio(response.agent_audio);
        } else {
          updateState('idle');
        }
        
        console.log('✅ Voice chat completed', { 
          duration: response.duration_ms,
          userText: response.user_text?.slice(0, 50)
        });
      } else {
        throw new Error('Voice chat response indicated failure');
      }
    } catch (err) {
      console.error('Voice chat failed:', err);
      setError(err instanceof Error ? err.message : 'Unknown error');
      setAgentResponse('抱歉，处理您的语音请求时出错了。');
      updateState('error');
      
      // 3秒后恢复空闲状态
      setTimeout(() => {
        setVoiceState(prev => prev === 'error' ? 'idle' : prev);
      }, 3000);
    }
  }, [updateState]);

  // 播放 Agent 音频
  // 支持 WAV (DashScope SDK) 和 MP3 (OpenAI compatible) 格式
  const playAgentAudio = useCallback((base64Audio: string) => {
    if (!audioRef.current) {
      audioRef.current = new Audio();
    }
    
    const audio = audioRef.current;
    
    // 检测音频格式 (WAV 文件以 "UklGR" 开头，MP3 以 "//uQ" 或其他开头)
    const isWav = base64Audio.startsWith('UklGR') || base64Audio.startsWith('Ukl');
    const mimeType = isWav ? 'audio/wav' : 'audio/mp3';
    
    audio.src = `data:${mimeType};base64,${base64Audio}`;
    console.log(`🔊 Playing audio (format: ${mimeType})`);
    
    audio.onended = () => {
      updateState('idle');
    };
    
    audio.onerror = () => {
      console.error('Audio playback failed');
      updateState('idle');
    };
    
    audio.play().catch(err => {
      console.error('Failed to play audio:', err);
      updateState('idle');
    });
  }, [updateState]);

  // 唤醒词回调 - 触发录音
  const handleWakeWord = useCallback(() => {
    console.log("🎉 Wake word 'Hi Lavis' detected! Triggering recording...");
    if (voiceState === 'idle') {
      // 设置唤醒词检测标志（用于 App 切换到聊天模式）
      setWakeWordDetected(true);
      // 延迟重置标志
      setTimeout(() => setWakeWordDetected(false), 500);

      playDing();
      setTranscribedText('');
      setAgentResponse('');
      setAgentAudio(null);
      setError(null);
      updateState('listening');
      recorderStart();
    }
  }, [voiceState, updateState, recorderStart]);

  // 初始化唤醒词 Hook (始终监听，除非正在处理语音)
  // 优先使用 publicPath（推荐），其次 Base64
  // 只在应用启动且空闲时才监听
  const { isListening: isWakeWordListening, error: wakeWordError } = useWakeWord({
    accessKey: import.meta.env.VITE_PICOVOICE_KEY,
    keywordPath: import.meta.env.VITE_WAKE_WORD_PATH || '/hi-lavis.ppn',
    keywordBase64: import.meta.env.VITE_WAKE_WORD_BASE64,
    onWake: handleWakeWord,
    enabled: isAppStarted && voiceState === 'idle' // 只有在应用启动且空闲时才监听
  });

  // Debug: 打印环境变量状态
  useEffect(() => {
    const picoKey = import.meta.env.VITE_PICOVOICE_KEY;
    const wakeWordPath = import.meta.env.VITE_WAKE_WORD_PATH || '/hi-lavis.ppn';
    const wakeWordB64 = import.meta.env.VITE_WAKE_WORD_BASE64;
    
    console.log('🔧 GlobalVoice: Environment variables check:');
    console.log(`   VITE_PICOVOICE_KEY: ${picoKey ? '✅ Set (' + picoKey.slice(0, 15) + '...)' : '❌ NOT SET'}`);
    console.log(`   VITE_WAKE_WORD_PATH: ${wakeWordPath} (default: /hi-lavis.ppn)`);
    console.log(`   VITE_WAKE_WORD_BASE64: ${wakeWordB64 ? '✅ Set (backup)' : '❌ NOT SET'}`);
  }, []);

  // 监听录音完成，自动上传
  useEffect(() => {
    if (audioBlob && voiceState === 'listening' && !isTooShort) {
      handleVoiceChat(audioBlob);
    } else if (isTooShort && voiceState === 'listening') {
      // 录音过短或全程静音，直接回到 idle 状态
      console.log('⏭️ Recording too short or full silence, skipping upload and returning to idle');
      updateState('idle');
    }
  }, [audioBlob, voiceState, isTooShort, handleVoiceChat, updateState]);

  // 同步录音状态
  useEffect(() => {
    if (isRecording && voiceState !== 'listening') {
      updateState('listening');
    }
  }, [isRecording, voiceState, updateState]);

  // 合并错误信息
  useEffect(() => {
    if (wakeWordError && !error) {
      setError(wakeWordError);
    }
    if (recorderError && !error) {
      setError(recorderError);
    }
  }, [wakeWordError, recorderError, error]);

  // 手动开始录音
  const startRecording = useCallback(() => {
    setTranscribedText('');
    setAgentResponse('');
    setAgentAudio(null);
    setError(null);
    
    playDing();
    updateState('listening');
    recorderStart();
  }, [updateState, recorderStart]);

  // 手动停止录音
  const stopRecording = useCallback(() => {
    recorderStop();
  }, [recorderStop]);

  // 重置状态
  const reset = useCallback(() => {
    setVoiceState('idle');
    setTranscribedText('');
    setAgentResponse('');
    setAgentAudio(null);
    setError(null);
    
    // 停止音频播放
    if (audioRef.current) {
      audioRef.current.pause();
      audioRef.current.src = '';
    }
  }, []);

  // 清理
  useEffect(() => {
    return () => {
      if (audioRef.current) {
        audioRef.current.pause();
        audioRef.current.src = '';
      }
    };
  }, []);

  return {
    voiceState,
    isWakeWordListening,
    isRecording,
    isRecorderReady, // 录音机准备好状态
    transcribedText,
    agentResponse,
    agentAudio,
    error,
    wakeWordDetected,
    startRecording,
    stopRecording,
    reset,
  };
}

