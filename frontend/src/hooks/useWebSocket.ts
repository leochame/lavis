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

const INITIAL_STATE: WorkflowState = {
  planId: null,
  userGoal: null,
  steps: [],
  progress: 0,
  status: 'idle',
  currentStepId: null,
  logs: [],
};

export function useWebSocket(url: string, ttsCallbacks?: TtsEventCallbacks) {
  const [status, setStatus] = useState<ConnectionStatus>('disconnected');
  const [workflow, setWorkflow] = useState<WorkflowState>(INITIAL_STATE);
  const [lastEvent, setLastEvent] = useState<WorkflowEvent | null>(null);
  const [sessionId, setSessionId] = useState<string | null>(null);

  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimeoutRef = useRef<number | null>(null);
  const retryCountRef = useRef(0);
  const isUnmountedRef = useRef(false);
  const ttsCallbacksRef = useRef(ttsCallbacks);

  // 更新回调引用
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
    console.log('🔍 [WS] 处理消息:', type, 'data:', data);

    switch (type) {
      case 'connected':
        // 保存服务器返回的 sessionId
        // 消息格式：{ type: "connected", data: { sessionId: "...", message: "..." }, timestamp: ... }
        console.log('✅ [WS] 收到 connected 消息:', message);
        const sessionIdValue = data?.sessionId as string | undefined;
        if (sessionIdValue) {
          console.log('✅ [WS] 保存 sessionId:', sessionIdValue);
          setSessionId(sessionIdValue);
        } else {
          console.warn('⚠️ [WS] connected 消息中未找到 sessionId，data:', data);
        }
        break;

      case 'plan_created':
        if (!data) {
          console.warn('[WS] ⚠️ plan_created message missing data');
          break;
        }
        console.log('📋 [WS] 处理 plan_created:', {
          planId: data.planId,
          userGoal: data.userGoal,
          stepsCount: (data.steps as PlanStepEvent[])?.length || 0,
          steps: data.steps
        });
        setWorkflow((prev) => {
          const newState = {
            ...prev,
            planId: data.planId as string,
            userGoal: data.userGoal as string,
            steps: (data.steps as PlanStepEvent[]) || [],
            progress: 0,
            status: 'planning' as const,
            currentStepId: null,
          };
          console.log('📋 [WS] 更新 workflow 状态为 planning:', newState);
          return newState;
        });
        break;

      case 'step_started':
        if (!data) {
          console.warn('[WS] ⚠️ step_started message missing data');
          break;
        }
        console.log('🔄 [WS] 处理 step_started:', {
          stepId: data.stepId,
          progress: data.progress,
          description: data.description
        });
        setWorkflow((prev) => {
          const newState = {
            ...prev,
            status: 'executing' as const,
            currentStepId: data.stepId as number,
            progress: data.progress as number,
            steps: prev.steps.map((step) =>
              step.id === data.stepId ? { ...step, status: 'IN_PROGRESS' } : step
            ),
          };
          console.log('🔄 [WS] 更新 workflow 状态为 executing:', newState);
          return newState;
        });
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

      case 'plan_completed':
        setWorkflow((prev) => ({
          ...prev,
          status: 'completed',
          progress: 100,
        }));
        // 注意：voice_announcement 是单独的事件，会在 plan_completed 之后通过 WebSocket 发送
        // 如果后端在 plan_completed 的 data 中包含了语音播报信息，可以在这里处理
        if (data && (data as any).voiceAnnouncement) {
          handleVoiceAnnouncement((data as any).voiceAnnouncement).catch((error) => {
            console.error('[WS] Failed to handle voice announcement:', error);
          });
        }
        break;

      case 'plan_failed':
        setWorkflow((prev) => ({
          ...prev,
          status: 'failed',
        }));
        // 如果有错误信息，记录到日志
        if (data && (data as any).reason) {
          console.error('[WS] 计划失败:', (data as any).reason);
        }
        break;

      case 'execution_error':
        // 处理执行错误事件
        if (!data) {
          console.warn('[WS] ⚠️ execution_error message missing data');
          break;
        }
        const errorMessage = (data as any).errorMessage as string;
        const errorType = (data as any).errorType as string;
        const errorPlanId = (data as any).planId as string;
        
        console.error('[WS] ❌ 执行错误:', {
          errorType,
          errorMessage,
          planId: errorPlanId
        });
        
        // 更新工作流状态为失败
        setWorkflow((prev) => ({
          ...prev,
          status: 'failed',
          // 将错误信息添加到日志
          logs: [
            ...prev.logs.slice(-49),
            {
              level: 'error',
              message: `执行错误 [${errorType}]: ${errorMessage}`,
              timestamp: (data as any).timestamp as number || Date.now(),
            },
          ],
        }));
        break;

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

      case 'voice_announcement':
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

      // ==========================================
      // TTS 异步推送事件处理
      // ==========================================
      case 'tts_audio':
        // 收到 TTS 音频片段
        if (!data) {
          break;
        }
        if (ttsCallbacksRef.current?.onTtsAudio) {
          ttsCallbacksRef.current.onTtsAudio({
            type: 'tts_audio',
            requestId: data.requestId as string,
            data: data.data as string,
            index: data.index as number,
            isLast: data.isLast as boolean,
          });
        }
        break;

      case 'tts_skip':
        // TTS 被跳过（不需要语音回复）
        if (!data) {
          break;
        }
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
  }, []);

  const connect = useCallback(function connectFn() {
    if (wsRef.current?.readyState === WebSocket.OPEN || wsRef.current?.readyState === WebSocket.CONNECTING) return;
    if (isUnmountedRef.current) return;

    setStatus('connecting');

    try {
      const ws = new WebSocket(url);
      wsRef.current = ws;

      ws.onopen = () => {
        if (isUnmountedRef.current) {
            ws.close();
            return;
        }
        console.log('🔌 [WS] WebSocket 连接已建立:', url);
        setStatus('connected');
        retryCountRef.current = 0; // 重置重试计数
        // Subscribe to workflow updates
        const subscribeMsg = JSON.stringify({ type: 'subscribe' });
        console.log('📤 [WS] 发送订阅消息:', subscribeMsg);
        ws.send(subscribeMsg);
      };

      ws.onclose = (event) => {
        if (isUnmountedRef.current) return;
        
        console.log('🔌 [WS] WebSocket 连接关闭:', {
          code: event.code,
          reason: event.reason,
          wasClean: event.wasClean
        });
        setStatus('disconnected');
        
        // 增强交互：指数退避重连算法
        // 延时: 1s, 2s, 4s, 8s, 16s, max 30s
        const backoffDelay = Math.min(1000 * Math.pow(2, retryCountRef.current), 30000);
        retryCountRef.current++;
        console.log(`🔄 [WS] ${backoffDelay}ms 后尝试重连 (重试次数: ${retryCountRef.current})`);

        reconnectTimeoutRef.current = window.setTimeout(() => {
          connectFn();
        }, backoffDelay);
      };

      ws.onerror = (error) => {
        console.error('❌ [WS] WebSocket 错误:', error);
        // onerror 之后通常会触发 onclose，所以重连逻辑放在 onclose
      };

      ws.onmessage = (event) => {
        try {
          const rawData = event.data;
          console.log('📩 [WS] 收到原始消息:', rawData);
          const message = JSON.parse(rawData) as WorkflowEvent;
          console.log('📩 [WS] 解析后的消息:', {
            type: message.type,
            hasData: !!message.data,
            dataKeys: message.data ? Object.keys(message.data) : [],
            timestamp: message.timestamp,
            fullMessage: message
          });
          setLastEvent(message);
          handleMessage(message);
        } catch (e) {
          console.error('[WS] ❌ 解析消息失败:', e, '原始数据:', event.data);
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

  return {
    connected: status === 'connected',
    status, // 暴露具体的连接状态 ('connecting' | 'connected' | 'disconnected')
    sessionId, // WebSocket Session ID（用于 voice-chat 请求）
    workflow,
    lastEvent,
    resetWorkflow,
    sendMessage, // 暴露发送方法
  };
}