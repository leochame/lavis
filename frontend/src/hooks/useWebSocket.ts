import { useState, useEffect, useCallback, useRef } from 'react';

import { agentApi } from '../api/agentApi';
import { audioService } from '../services/audioService';
import { useUIStore } from '../store/uiStore';

export interface WorkflowEvent {
  type: string;
  data: Record<string, unknown>;
  timestamp: number;
}

/**
 * TTS 音频事件（从 WebSocket 接收）
 */
export interface TtsAudioEvent {
  type: 'tts_audio';
  requestId: string;
  data: string;  // Base64 音频数据
  index: number;
  isLast: boolean;
}

/**
 * TTS 跳过事件
 */
export interface TtsSkipEvent {
  type: 'tts_skip';
  requestId: string;
  reason: string;
}

/**
 * TTS 错误事件
 */
export interface TtsErrorEvent {
  type: 'tts_error';
  requestId: string;
  error: string;
}

export type TtsEvent = TtsAudioEvent | TtsSkipEvent | TtsErrorEvent;

export interface PlanStepEvent {
  id: number;
  description: string;
  type: string;
  status: 'PENDING' | 'IN_PROGRESS' | 'SUCCESS' | 'FAILED' | 'SKIPPED';
  resultSummary?: string;
  executionTimeMs?: number;
}

export interface WorkflowState {
  planId: string | null;
  userGoal: string | null;
  steps: PlanStepEvent[];
  progress: number;
  status: 'idle' | 'planning' | 'executing' | 'completed' | 'failed';
  currentStepId: number | null;
  logs: Array<{ level: string; message: string; timestamp: number }>;
}

export type ConnectionStatus = 'disconnected' | 'connecting' | 'connected';

/**
 * TTS 事件回调接口
 */
export interface TtsEventCallbacks {
  onTtsAudio?: (event: TtsAudioEvent) => void;
  onTtsSkip?: (event: TtsSkipEvent) => void;
  onTtsError?: (event: TtsErrorEvent) => void;
}

export interface UseWebSocketOptions {
  infiniteReconnect?: boolean;
  trackTransitions?: boolean;
}

const INITIAL_STATE: WorkflowState = {
  planId: null,
  userGoal: null,
  steps: [],
  progress: 0,
  status: 'idle',
  currentStepId: null,
  logs: [],
};

export function useWebSocket(url: string, ttsCallbacks?: TtsEventCallbacks, options?: UseWebSocketOptions) {
  const [status, setStatus] = useState<ConnectionStatus>('disconnected');
  const [workflow, setWorkflow] = useState<WorkflowState>(INITIAL_STATE);
  const [lastEvent, setLastEvent] = useState<WorkflowEvent | null>(null);
  const [sessionId, setSessionId] = useState<string | null>(null);
  // 新增：跟踪 TTS 是否正在生成（用于保持工作状态指示器）
  const [isTtsGenerating, setIsTtsGenerating] = useState(false);

  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimeoutRef = useRef<number | null>(null);
  const retryCountRef = useRef(0);
  const isUnmountedRef = useRef(false);
  const ttsCallbacksRef = useRef(ttsCallbacks);
  const transitionSourceRef = useRef<string>('init');
  const previousConnectionRef = useRef<ConnectionStatus>('disconnected');
  const previousWorkflowRef = useRef<WorkflowState['status']>('idle');
  const infiniteReconnect = options?.infiniteReconnect ?? false;
  const trackTransitions = options?.trackTransitions ?? true;

  // 更新回调引用：保持 connect / handleMessage 稳定，同时总是使用最新回调
  useEffect(() => {
    ttsCallbacksRef.current = ttsCallbacks;
  }, [ttsCallbacks]);
  
  // 获取 UI Store 的 setTtsPlaying 方法
  const setTtsPlaying = useUIStore((s) => s.setTtsPlaying);

  // 处理语音播报：调用 TTS API 并播放音频
  // 使用单例 Audio 服务，实现"消费即焚"策略
  const handleVoiceAnnouncement = useCallback(async (text: string) => {
    if (!text || text.trim().length === 0) {
      return;
    }

    try {
      // 设置 TTS 播放状态
      setTtsPlaying(true);
      
      // 调用后端 TTS API
      const ttsResponse = await agentApi.tts(text);
      
      if (!ttsResponse.success || !ttsResponse.audio) {
        console.error('[WS] TTS API failed');
        setTtsPlaying(false);
        return;
      }

      // 将 Base64 音频转换为 Blob
      const audioBlob = base64ToBlob(ttsResponse.audio, `audio/${ttsResponse.format}`);
      const audioUrl = URL.createObjectURL(audioBlob);
      
      // 使用单例 Audio 服务播放（自动清理 URL）
      try {
        await audioService.play(audioUrl, () => {
          // 播放结束回调，更新状态
          setTtsPlaying(false);
        });
      } catch (error) {
        console.error('[WS] Failed to play audio:', error);
        setTtsPlaying(false);
        // 如果播放失败，手动清理 URL
        URL.revokeObjectURL(audioUrl);
      }
    } catch (error) {
      console.error('[WS] Voice announcement error:', error);
      setTtsPlaying(false);
    }
  }, [setTtsPlaying]);

  // 将 Base64 字符串转换为 Blob
  const base64ToBlob = (base64: string, mimeType: string): Blob => {
    const byteCharacters = atob(base64);
    const byteNumbers = new Array(byteCharacters.length);
    for (let i = 0; i < byteCharacters.length; i++) {
      byteNumbers[i] = byteCharacters.charCodeAt(i);
    }
    const byteArray = new Uint8Array(byteNumbers);
    return new Blob([byteArray], { type: mimeType });
  };

  // 消息处理逻辑
  const handleMessage = useCallback((message: WorkflowEvent) => {
    const { type, data } = message;
    transitionSourceRef.current = type;

    switch (type) {
      case 'connected': {
        // 保存服务器返回的 sessionId
        const sessionIdValue = data?.sessionId as string | undefined;
        if (sessionIdValue) {
          setSessionId(sessionIdValue);
        } else {
          console.warn('⚠️ [WS] sessionId not found in connected message');
        }
        break;
      }

      case 'plan_created':
        if (!data) {
          console.warn('[WS] plan_created message missing data');
          break;
        }
        setWorkflow((prev) => ({
          ...prev,
          planId: data.planId as string,
          userGoal: data.userGoal as string,
          steps: (data.steps as PlanStepEvent[]) || [],
          progress: 0,
          status: 'planning' as const,
          currentStepId: null,
        }));
        break;

      case 'step_started':
        if (!data) {
          console.warn('[WS] step_started message missing data');
          break;
        }
        setWorkflow((prev) => ({
          ...prev,
          status: 'executing' as const,
          currentStepId: data.stepId as number,
          progress: data.progress as number,
          steps: prev.steps.map(
            (step): PlanStepEvent =>
              step.id === data.stepId
                ? { ...step, status: 'IN_PROGRESS' }
                : step
          ),
        }));
        break;

      case 'step_completed':
        if (!data) {
          console.warn('[WS] step_completed message missing data');
          break;
        }
        setWorkflow((prev) => ({
          ...prev,
          progress: data.progress as number,
          steps: prev.steps.map((step) =>
            step.id === data.stepId
              ? {
                  ...step,
                  status: 'SUCCESS',
                  resultSummary: data.resultSummary as string,
                  executionTimeMs: data.executionTimeMs as number,
                }
              : step
          ),
        }));
        break;

      case 'step_failed':
        if (!data) {
          console.warn('[WS] step_failed message missing data');
          break;
        }
        setWorkflow((prev) => ({
          ...prev,
          progress: data.progress as number,
          steps: prev.steps.map((step) =>
            step.id === data.stepId
              ? { ...step, status: 'FAILED', resultSummary: data.reason as string }
              : step
          ),
        }));
        break;

      case 'plan_completed': {
        setWorkflow((prev) => ({
          ...prev,
          status: 'completed',
          progress: 100,
        }));
        // 注意：voice_announcement 是单独的事件，会在 plan_completed 之后通过 WebSocket 发送
        // 如果后端在 plan_completed 的 data 中包含了语音播报信息，可以在这里处理
        const planCompletedData = data as { voiceAnnouncement?: string } | undefined;
        if (planCompletedData?.voiceAnnouncement) {
          handleVoiceAnnouncement(planCompletedData.voiceAnnouncement).catch((error) => {
            console.error('[WS] Failed to handle voice announcement:', error);
          });
        }
        break;
      }

      case 'plan_failed': {
        setWorkflow((prev) => ({
          ...prev,
          status: 'failed',
        }));
        // 如果有错误信息，记录到日志
        const failedData = data as { reason?: string } | undefined;
        if (failedData?.reason) {
          console.error('[WS] Plan failed:', failedData.reason);
        }
        break;
      }

      case 'execution_error': {
        // 处理执行错误事件
        if (!data) {
          console.warn('[WS] execution_error message missing data');
          break;
        }
        const errorData = data as {
          errorMessage?: string;
          errorType?: string;
          planId?: string;
          timestamp?: number;
        };
        const errorMessage = errorData.errorMessage ?? '';
        const errorType = errorData.errorType ?? '';

        console.error('[WS] Execution error:', errorType, errorMessage);

        // 更新工作流状态为失败
        setWorkflow((prev) => ({
          ...prev,
          status: 'failed',
          // 将错误信息添加到日志
          logs: [
            ...prev.logs.slice(-49),
            {
              level: 'error',
              message: `Execution error [${errorType}]: ${errorMessage}`,
              timestamp: errorData.timestamp || Date.now(),
            },
          ],
        }));
        break;
      }

      case 'thinking':
        setWorkflow((prev) => ({
          ...prev,
          status: 'planning',
        }));
        break;

      case 'log':
        if (!data) {
          console.warn('[WS] log message missing data');
          break;
        }
        setWorkflow((prev) => ({
          ...prev,
          logs: [
            ...prev.logs.slice(-49),
            {
              level: data.level as string,
              message: data.message as string,
              timestamp: data.timestamp as number,
            },
          ],
        }));
        break;

      case 'voice_announcement': {
        // 处理语音播报事件：调用后端 TTS API 并播放
        if (!data) {
          break;
        }
        const announcementText = data.text as string;
        if (!announcementText || announcementText.trim().length === 0) {
          break;
        }
        handleVoiceAnnouncement(announcementText).catch((error) => {
          console.error('[WS] Failed to play voice announcement:', error);
        });
        break;
      }

      // ==========================================
      // TTS 异步推送事件处理
      // ==========================================
      case 'tts_audio':
        // 收到 TTS 音频片段
        if (!data) {
          break;
        }
        // 标记 TTS 正在生成
        setIsTtsGenerating(true);
        if (ttsCallbacksRef.current?.onTtsAudio) {
          ttsCallbacksRef.current.onTtsAudio({
            type: 'tts_audio',
            requestId: data.requestId as string,
            data: data.data as string,
            index: data.index as number,
            isLast: data.isLast as boolean,
          });
        }
        // 如果是最后一个音频片段，标记 TTS 生成完成
        if (data.isLast) {
          setIsTtsGenerating(false);
        }
        break;

      case 'tts_skip':
        // TTS 被跳过（不需要语音回复）
        if (!data) {
          break;
        }
        // TTS 跳过，标记生成完成
        setIsTtsGenerating(false);
        if (ttsCallbacksRef.current?.onTtsSkip) {
          ttsCallbacksRef.current.onTtsSkip({
            type: 'tts_skip',
            requestId: data.requestId as string,
            reason: data.reason as string,
          });
        }
        break;

      case 'tts_error':
        // TTS 生成失败
        if (!data) {
          break;
        }
        console.error('[WS] TTS error:', data.error);
        // TTS 错误，标记生成完成
        setIsTtsGenerating(false);
        if (ttsCallbacksRef.current?.onTtsError) {
          ttsCallbacksRef.current.onTtsError({
            type: 'tts_error',
            requestId: data.requestId as string,
            error: data.error as string,
          });
        }
        break;

      default:
        // Handle custom events or unknown events silently
        break;
    }
  }, [handleVoiceAnnouncement]);

  const connect = useCallback(function connectFn() {
    if (wsRef.current?.readyState === WebSocket.OPEN || wsRef.current?.readyState === WebSocket.CONNECTING) return;
    if (isUnmountedRef.current) return;

    transitionSourceRef.current = 'connect()';
    setStatus('connecting');

    try {
      const ws = new WebSocket(url);
      wsRef.current = ws;

      ws.onopen = () => {
        if (isUnmountedRef.current) {
            ws.close();
            return;
        }
        transitionSourceRef.current = 'onopen';
        setStatus('connected');
        retryCountRef.current = 0; // 重置重试计数
        // Subscribe to workflow updates
        const subscribeMsg = JSON.stringify({ type: 'subscribe' });
        ws.send(subscribeMsg);
      };

      ws.onclose = () => {
        if (isUnmountedRef.current) return;

        transitionSourceRef.current = 'onclose';
        setStatus('disconnected');

        // 胶囊模式可无限重连；聊天模式保持上限以避免异常场景持续消耗资源
        const maxRetryCount = infiniteReconnect ? Number.POSITIVE_INFINITY : 20;
        if (Number.isFinite(maxRetryCount) && retryCountRef.current >= maxRetryCount) {
          console.log('[WS] Maximum retry attempts reached, stopping reconnect');
          return;
        }

        // 指数退避重连: 1s, 2s, 4s... 最大 30s
        const backoffExponent = Math.min(retryCountRef.current, 10);
        const backoffDelay = Math.min(1000 * Math.pow(2, backoffExponent), 30000);
        retryCountRef.current++;

        reconnectTimeoutRef.current = window.setTimeout(() => {
          connectFn();
        }, backoffDelay);
      };

      ws.onerror = (error) => {
        console.error('[WS] WebSocket error:', error);
        // onerror 之后通常会触发 onclose，所以重连逻辑放在 onclose
      };

      ws.onmessage = (event) => {
        try {
          const rawData = event.data;
          const message = JSON.parse(rawData) as WorkflowEvent;

          setLastEvent(message);
          handleMessage(message);
        } catch (e) {
          console.error('[WS] Failed to parse message:', e);
        }
      };
    } catch (e) {
      console.error('[WS] Connection failed:', e);
      setStatus('disconnected');
    }
  }, [url, handleMessage]);

  // 发送消息的方法（增强交互性）
  const sendMessage = useCallback((type: string, data: Record<string, unknown> = {}) => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify({ type, data, timestamp: Date.now() }));
    }
  }, []);

  const resetWorkflow = useCallback(() => {
    setWorkflow(INITIAL_STATE);
  }, []);

  useEffect(() => {
    isUnmountedRef.current = false;
    connect();

    return () => {
      isUnmountedRef.current = true;
      if (reconnectTimeoutRef.current) {
        clearTimeout(reconnectTimeoutRef.current);
      }
      if (wsRef.current) {
        wsRef.current.close();
      }
    };
  }, [connect]);

  useEffect(() => {
    if (!trackTransitions) {
      previousConnectionRef.current = status;
      return;
    }
    const previous = previousConnectionRef.current;
    const current = status;
    if (previous === current) {
      return;
    }

    const allowedTransitions: Record<ConnectionStatus, ConnectionStatus[]> = {
      disconnected: ['connecting'],
      connecting: ['connected', 'disconnected'],
      connected: ['disconnected'],
    };
    const isAllowed = allowedTransitions[previous].includes(current);
    const source = transitionSourceRef.current;
    if (!isAllowed) {
      console.warn(`[WS][Transition] connection ${previous} -> ${current} via ${source} (unexpected)`);
    } else {
      console.debug(`[WS][Transition] connection ${previous} -> ${current} via ${source}`);
    }
    previousConnectionRef.current = current;
  }, [status, trackTransitions]);

  useEffect(() => {
    if (!trackTransitions) {
      previousWorkflowRef.current = workflow.status;
      return;
    }
    const previous = previousWorkflowRef.current;
    const current = workflow.status;
    if (previous === current) {
      return;
    }

    const allowedTransitions: Record<WorkflowState['status'], WorkflowState['status'][]> = {
      idle: ['planning', 'executing', 'failed', 'completed'],
      planning: ['planning', 'executing', 'failed', 'completed', 'idle'],
      executing: ['executing', 'planning', 'completed', 'failed', 'idle'],
      completed: ['planning', 'executing', 'idle'],
      failed: ['planning', 'executing', 'idle'],
    };
    const isAllowed = allowedTransitions[previous].includes(current);
    const source = transitionSourceRef.current;
    if (!isAllowed) {
      console.warn(`[WS][Transition] workflow ${previous} -> ${current} via ${source} (unexpected)`);
    } else {
      console.debug(`[WS][Transition] workflow ${previous} -> ${current} via ${source}`);
    }
    previousWorkflowRef.current = current;
  }, [workflow.status, trackTransitions]);

  return {
    connected: status === 'connected',
    status, // 暴露具体的连接状态 ('connecting' | 'connected' | 'disconnected')
    sessionId, // WebSocket Session ID（用于 voice-chat 请求）
    workflow,
    lastEvent,
    isTtsGenerating, // TTS 是否正在生成（用于保持工作状态指示器）
    resetWorkflow,
    sendMessage, // 暴露发送方法
  };
}
