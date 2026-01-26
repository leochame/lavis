import { useState, useEffect, useRef, useCallback } from 'react';
import { agentApi } from '../api/agentApi';
import { useVoiceRecorder } from './useVoiceRecorder';
import { useVoskWakeWord } from './useVoskWakeWord';
import type { TtsAudioEvent, TtsSkipEvent, TtsErrorEvent } from './useWebSocket';

/**
 * 语音交互状态
 */
export type VoiceState = 'idle' | 'listening' | 'processing' | 'speaking' | 'awaiting_audio' | 'error';

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
  /** Agent 回复音频 (Base64) - 兼容旧版同步模式 */
  agentAudio: string | null;
  /** 错误信息 */
  error: string | null;
  /** 唤醒词是否被检测到（用于切换到聊天模式） */
  wakeWordDetected: boolean;
  /** 当前请求 ID（用于匹配异步 TTS） */
  currentRequestId: string | null;
  /** 手动开始录音 */
  startRecording: () => void;
  /** 手动停止录音 */
  stopRecording: () => void;
  /** 重置状态 */
  reset: () => void;
  /** TTS 事件回调（供 useWebSocket 使用） */
  ttsCallbacks: {
    onTtsAudio: (event: TtsAudioEvent) => void;
    onTtsSkip: (event: TtsSkipEvent) => void;
    onTtsError: (event: TtsErrorEvent) => void;
  };
}

/**
 * 音频队列项
 */
interface AudioQueueItem {
  data: string;  // Base64 音频数据
  index: number;
  isLast: boolean;
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
    } catch (e) {
      console.warn("AudioContext not supported or failed", e);
    }
  }
  return globalAudioContext;
};

/**
 * 确保 AudioContext 处于活跃状态
 * 在 Electron 环境下，即使设置了 backgroundThrottling: false，
 * AudioContext 仍可能因长时间无音频输入/输出而变为 suspended
 */
const ensureAudioContextActive = async (): Promise<void> => {
  const audioContext = getAudioContext();
  if (!audioContext) return;

  if (audioContext.state === 'suspended') {
    try {
      await audioContext.resume();
    } catch (e) {
      console.warn('Failed to resume AudioContext:', e);
    }
  }
};

/**
 * 简单的 "Ding" 提示音生成器
 * 使用全局 AudioContext 合成一个清脆的提示音（上升音调，表示开始）
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

    // 上升音调，表示开始录音
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
 * 停止录音提示音生成器
 * 使用下降音调，表示录音结束
 */
const playStopSound = () => {
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

    // 下降音调，表示停止录音
    oscillator.type = 'sine';
    oscillator.frequency.setValueAtTime(880, audioContext.currentTime); // A5
    oscillator.frequency.exponentialRampToValueAtTime(660, audioContext.currentTime + 0.1); // E5
    oscillator.frequency.exponentialRampToValueAtTime(440, audioContext.currentTime + 0.2); // A4

    gainNode.gain.setValueAtTime(0.3, audioContext.currentTime);
    gainNode.gain.exponentialRampToValueAtTime(0.01, audioContext.currentTime + 0.25);

    oscillator.start();
    oscillator.stop(audioContext.currentTime + 0.25);
  } catch (e) {
    console.warn("Stop sound playback failed", e);
  }
};

/**
 * 全局语音交互 Hook
 *
 * 核心功能:
 * 1. 唤醒词监听 (始终运行，除非正在处理其他语音任务)
 * 2. 语音录制
 * 3. 语音对话 (STT -> Agent -> 异步 TTS via WebSocket)
 * 4. 状态管理
 * 5. 音频队列管理（支持流式 TTS 播放）
 *
 * 设计原则:
 * - 这个 Hook 应该在 App.tsx 中初始化，确保生命周期最长
 * - 无论 UI 如何切换，唤醒词监听始终存在
 * - 必须在用户点击开始后才初始化音频功能（浏览器安全策略）
 * - 文字先行，音频异步推送（降低延迟）
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
  const [isRecorderReady, setIsRecorderReady] = useState(false);
  const [currentRequestId, setCurrentRequestId] = useState<string | null>(null);

  // 音频播放器引用
  const audioRef = useRef<HTMLAudioElement | null>(null);
  // 防止重复播放的标志
  const isPlayingRef = useRef<boolean>(false);
  // 音频队列（用于流式 TTS）
  const audioQueueRef = useRef<AudioQueueItem[]>([]);
  // 是否已收到最后一个音频片段
  const receivedLastRef = useRef<boolean>(false);
  // 防止重复处理同一个 blob 的标志
  const isProcessingRef = useRef<boolean>(false);
  // 追踪最后处理的 blob 标识（size + timestamp）
  const lastProcessedBlobRef = useRef<string | null>(null);

  // 初始化录音 Hook
  const {
    isRecording,
    isRecordingReady,
    startRecording: recorderStart,
    stopRecording: recorderStop,
    audioBlob,
    error: recorderError,
    isTooShort,
    clearAudioBlob,
  } = useVoiceRecorder();

  // 追踪录音机是否已准备好
  useEffect(() => {
    if (isRecordingReady) {
      setIsRecorderReady(true);
    }
  }, [isRecordingReady]);

  // 更新状态的便捷函数
  // 使用函数式更新避免闭包问题
  const updateState = useCallback((newState: VoiceState) => {
    setVoiceState(newState);
  }, []);

  // 播放队列中的下一个音频
  const playNextInQueue = useCallback(() => {
    if (audioQueueRef.current.length === 0) {
      // 队列为空
      if (receivedLastRef.current) {
        // 已收到最后一个片段且队列播放完毕，结束播放状态
        console.log('[Audio] Queue empty and received last, returning to idle');
        isPlayingRef.current = false;
        updateState('idle');
      }
      return;
    }

    // 按 index 排序，取出第一个
    audioQueueRef.current.sort((a, b) => a.index - b.index);
    const item = audioQueueRef.current.shift()!;

    console.log('[Audio] Playing audio chunk:', {
      index: item.index,
      isLast: item.isLast,
      dataLength: item.data.length,
      dataPreview: item.data.substring(0, 50)
    });

    if (!audioRef.current) {
      audioRef.current = new Audio();
    }

    const audio = audioRef.current;

    // 检测音频格式（通过 Base64 魔数）
    // WAV: "UklGR" (RIFF)
    // MP3: "//uQ" 或 "/+NI" 或 "SUQz" (ID3)
    // OGG: "T2dn" (OggS)
    // AAC: "//tQ" 或 "AAAA"
    let mimeType = 'audio/mpeg'; // 默认 MP3
    const dataPrefix = item.data.substring(0, 10);

    if (dataPrefix.startsWith('UklGR')) {
      mimeType = 'audio/wav';
    } else if (dataPrefix.startsWith('T2dn')) {
      mimeType = 'audio/ogg';
    } else if (dataPrefix.startsWith('AAAA') || dataPrefix.startsWith('//tQ')) {
      mimeType = 'audio/aac';
    } else if (dataPrefix.startsWith('//uQ') || dataPrefix.startsWith('/+NI') || dataPrefix.startsWith('SUQz')) {
      mimeType = 'audio/mpeg';
    }

    console.log('[Audio] Detected format:', mimeType, 'prefix:', dataPrefix);

    // 清理之前的监听器
    audio.onerror = null;
    audio.onended = null;

    // 使用 data URL 播放
    audio.src = `data:${mimeType};base64,${item.data}`;
    isPlayingRef.current = true;

    audio.onerror = (e) => {
      console.error('[Audio] Playback error:', e, 'src length:', audio.src.length);
      isPlayingRef.current = false;
      // 继续播放下一个
      playNextInQueue();
    };

    audio.onended = () => {
      console.log('[Audio] Chunk playback ended, index:', item.index);
      audio.src = '';
      isPlayingRef.current = false;
      // 继续播放下一个
      playNextInQueue();
    };

    audio.play().catch((err) => {
      console.error('[Audio] Failed to play:', err);
      isPlayingRef.current = false;
      playNextInQueue();
    });
  }, [updateState]);


  // TTS 事件回调
  const handleTtsAudio = useCallback((event: TtsAudioEvent) => {
    console.log('[TTS] Received audio event:', {
      requestId: event.requestId,
      index: event.index,
      isLast: event.isLast,
      dataLength: event.data?.length || 0
    });

    // 检查 requestId 是否匹配
    if (currentRequestId && event.requestId !== currentRequestId) {
      console.log('[TTS] Ignoring audio for different requestId:', event.requestId, 'current:', currentRequestId);
      return;
    }

    // 验证音频数据
    if (!event.data || event.data.length === 0) {
      console.warn('[TTS] Received empty audio data, skipping');
      return;
    }

    // 添加到队列
    audioQueueRef.current.push({
      data: event.data,
      index: event.index,
      isLast: event.isLast,
    });

    if (event.isLast) {
      receivedLastRef.current = true;
      console.log('[TTS] Received last audio chunk');
    }

    // 如果当前没有在播放，开始播放
    if (!isPlayingRef.current) {
      console.log('[TTS] Starting playback');
      updateState('speaking');
      playNextInQueue();
    }
  }, [currentRequestId, updateState, playNextInQueue]);

  const handleTtsSkip = useCallback((event: TtsSkipEvent) => {
    if (currentRequestId && event.requestId !== currentRequestId) {
      return;
    }

    // TTS 被跳过，直接回到 idle 状态
    if (voiceState === 'awaiting_audio') {
      updateState('idle');
    }
    setCurrentRequestId(null);
  }, [currentRequestId, voiceState, updateState]);

  const handleTtsError = useCallback((event: TtsErrorEvent) => {
    if (currentRequestId && event.requestId !== currentRequestId) {
      return;
    }

    console.error(`[TTS] Error: ${event.error}`);
    setError(`TTS 生成失败: ${event.error}`);
    if (voiceState === 'awaiting_audio') {
      updateState('idle');
    }
    setCurrentRequestId(null);
  }, [currentRequestId, voiceState, updateState]);

  // TTS 回调对象（供外部使用）
  const ttsCallbacks = {
    onTtsAudio: handleTtsAudio,
    onTtsSkip: handleTtsSkip,
    onTtsError: handleTtsError,
  };

  // 处理语音对话（异步 TTS 版本）
  // 文字先行，音频通过 WebSocket 异步推送
  const handleVoiceChat = useCallback(async (blob: Blob) => {
    // 生成 blob 的唯一标识（size + type，同一个录音应该具有相同的 size）
    const blobId = `${blob.size}-${blob.type}`;
    
    // 防止重复处理同一个 blob
    if (isProcessingRef.current) {
      console.log('[VoiceChat] Already processing a blob, skipping duplicate call');
      return;
    }
    
    // 检查是否是同一个 blob（通过 size 和 type 判断）
    if (lastProcessedBlobRef.current === blobId) {
      console.log('[VoiceChat] This blob was already processed, skipping duplicate call');
      return;
    }
    
    // 标记为正在处理
    isProcessingRef.current = true;
    lastProcessedBlobRef.current = blobId;
    
    updateState('processing');
    setError(null);

    // 重置音频队列状态
    audioQueueRef.current = [];
    receivedLastRef.current = false;

    try {
      const file = new File([blob], "recording.webm", { type: blob.type });
      const response = await agentApi.voiceChat(file);

      // 发送成功后立即清理 Blob（消费即焚）
      clearAudioBlob();

      if (response.success) {
        setTranscribedText(response.user_text);
        setAgentResponse(response.agent_text);
        setCurrentRequestId(response.request_id);

        // 播放 Ding 提示音，表示收到回复
        playDing();

        if (response.audio_pending) {
          // 音频将通过 WebSocket 异步推送
          updateState('awaiting_audio');
        } else {
          // 不需要 TTS，直接回到 idle
          updateState('idle');
        }
      } else {
        throw new Error('Voice chat response indicated failure');
      }
    } catch (err) {
      if (err instanceof Error) {
        console.error('[VoiceChat] Failed:', err.message);
      }
      
      // 即使失败也要清理 Blob
      clearAudioBlob();
      setError(err instanceof Error ? err.message : 'Unknown error');
      setAgentResponse('抱歉，处理您的语音请求时出错了。');
      updateState('error');

      // 3秒后恢复空闲状态（确保唤醒词监听能恢复）
      setTimeout(() => {
        setVoiceState(prev => {
          if (prev === 'error') {
            return 'idle';
          }
          return prev;
        });
      }, 3000);
    } finally {
      // 处理完成，重置处理标志
      isProcessingRef.current = false;
    }
  }, [updateState, clearAudioBlob]);

  // 唤醒词回调 - 触发录音
  const handleWakeWord = useCallback(() => {
    if (voiceState === 'idle') {
      // 确保 AudioContext 处于活跃状态（防止后台挂起）
      ensureAudioContextActive();

      // 设置唤醒词检测标志（用于 App 切换到聊天模式）
      setWakeWordDetected(true);
      // 延迟重置标志
      setTimeout(() => setWakeWordDetected(false), 500);

      playDing();
      setTranscribedText('');
      setAgentResponse('');
      setAgentAudio(null);
      setError(null);
      
      // 重置处理标志，允许新的录音被处理
      isProcessingRef.current = false;
      lastProcessedBlobRef.current = null;
      
      updateState('listening');
      recorderStart();
    }
  }, [voiceState, updateState, recorderStart]);

  // 初始化唤醒词 Hook (使用 Vosk 离线识别)
  // 只在应用启动且空闲时才监听（不在 processing/speaking 状态时监听）
  // 注意：当 voiceState 变为 'listening' 时，唤醒词监听会被暂停，但会在回到 'idle' 时自动恢复
  const { isListening: isWakeWordListening, error: wakeWordError } = useVoskWakeWord({
    wakeWord: import.meta.env.VITE_WAKE_WORD || 'hi lavis',
    modelPath: import.meta.env.VITE_VOSK_MODEL_PATH || '/models/vosk-model-small-en-us-0.15.tar.gz',
    onWake: handleWakeWord,
    enabled: isAppStarted && (voiceState === 'idle' || voiceState === 'error') // 在 idle 或 error 状态时监听
  });

  // 唤醒词错误处理
  useEffect(() => {
    if (wakeWordError) {
      console.error('[WakeWord] Error:', wakeWordError);
    }
  }, [wakeWordError]);

  // 监听录音完成，自动上传
  // 注意：即使 voiceState 不是 'listening'，只要 audioBlob 存在且不是太短，就应该上传
  // 因为录音可能在状态切换的瞬间完成
  useEffect(() => {
    // 如果 audioBlob 存在且不是太短，且（voiceState 是 listening 或录音已停止），则上传
    // 这样可以处理录音完成但状态可能已经变化的情况
    if (audioBlob && !isTooShort && (voiceState === 'listening' || !isRecording) && !isProcessingRef.current) {
      handleVoiceChat(audioBlob).catch((err) => {
        console.error('[VoiceChat] Failed:', err);
        // 处理失败时也要重置处理标志
        isProcessingRef.current = false;
      });
    } else if (isTooShort && (voiceState === 'listening' || !isRecording)) {
      // 录音过短或全程静音，直接回到 idle 状态
      updateState('idle');
    }
  }, [audioBlob, voiceState, isRecording, isTooShort, handleVoiceChat, updateState]);

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
    // 确保 AudioContext 处于活跃状态
    ensureAudioContextActive();

    setTranscribedText('');
    setAgentResponse('');
    setAgentAudio(null);
    setError(null);
    
    // 重置处理标志，允许新的录音被处理
    isProcessingRef.current = false;
    lastProcessedBlobRef.current = null;

    playDing();
    updateState('listening');
    recorderStart();
  }, [updateState, recorderStart]);

  // 手动停止录音
  const stopRecording = useCallback(() => {
    // 播放停止音效，给用户即时反馈
    playStopSound();
    recorderStop();
  }, [recorderStop]);

  // 重置状态
  const reset = useCallback(() => {
    setVoiceState('idle');
    setTranscribedText('');
    setAgentResponse('');
    setAgentAudio(null);
    setError(null);
    setCurrentRequestId(null);

    // 清空音频队列
    audioQueueRef.current = [];
    receivedLastRef.current = false;
    isPlayingRef.current = false;

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
    isRecorderReady,
    transcribedText,
    agentResponse,
    agentAudio,
    error,
    wakeWordDetected,
    currentRequestId,
    startRecording,
    stopRecording,
    reset,
    ttsCallbacks,
  };
}

