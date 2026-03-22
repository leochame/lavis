import { useState, useRef, useCallback } from 'react';

/**
 * Voice Recorder Hook
 *
 * 使用 MediaRecorder API 进行录音，支持智能静音检测（VAD）
 *
 * 核心算法（唤醒后语音输入）：
 * 1. 初始超时：唤醒后有 5 秒窗口期等待语音输入
 * 2. 动态延长：每次检测到语音，延长 2.5 秒超时
 * 3. 自动结束：超时无语音输入则自动停止
 * 4. 最大录音时长：60秒自动停止
 * 5. 全程静音检测：低能量音频自动丢弃
 */
export interface UseVoiceRecorderReturn {
  isRecording: boolean;
  isRecordingReady: boolean; // 录音机是否已准备好（获取到麦克风流后）
  startRecording: () => void;
  stopRecording: () => void;
  audioBlob: Blob | null;
  audioDuration: number;
  error: string | null;
  isTooShort: boolean; // 录音时长是否过短（< 0.5秒）
  clearAudioBlob: () => void; // 清理音频 Blob，释放内存
}

interface EnergyInfo {
  avgAudioEnergy: number;
  samplesCount: number;
}

type CleanupFunction = () => EnergyInfo | void;

export function useVoiceRecorder(): UseVoiceRecorderReturn {
  const [isRecording, setIsRecording] = useState(false);
  const [isRecordingReady, setIsRecordingReady] = useState(false);
  const [audioBlob, setAudioBlob] = useState<Blob | null>(null);
  const [audioDuration, setAudioDuration] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [isTooShort, setIsTooShort] = useState(false);

  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const audioChunksRef = useRef<Blob[]>([]);
  const startTimeRef = useRef<number>(0);

  // 静音检测
  const analyzeAudioLevel = useCallback((analyser: AnalyserNode) => {
    if (!mediaRecorderRef.current) return 0;

    // 使用时域数据计算 RMS；返回值范围约 -1.0 ~ 1.0
    const dataArray = new Float32Array(analyser.fftSize);
    analyser.getFloatTimeDomainData(dataArray);

    // 计算音频能量（RMS）
    let sum = 0;
    for (let i = 0; i < dataArray.length; i++) {
      sum += dataArray[i] * dataArray[i];
    }
    const rms = Math.sqrt(sum / dataArray.length);
    return rms;
  }, []);

  // 释放麦克风流
  const releaseStream = useCallback(() => {
    if (streamRef.current) {
      streamRef.current.getTracks().forEach((track: MediaStreamTrack) => {
        track.stop();
      });
      streamRef.current = null;
    }
  }, []);

  const stopRecording = useCallback(() => {
    if (mediaRecorderRef.current && mediaRecorderRef.current.state === 'recording') {
      const duration = (Date.now() - startTimeRef.current) / 1000;

      // 强制最少录音 0.5 秒
      if (duration < 0.5) {
        // 延迟停止，确保至少 0.5 秒，但避免竞态条件
        const remainingTime = (0.5 - duration) * 1000;
        setTimeout(() => {
          // 再次检查状态，避免重复停止
          if (mediaRecorderRef.current && mediaRecorderRef.current.state === 'recording') {
            mediaRecorderRef.current.stop();
            setIsRecording(false);
            releaseStream();
          }
        }, remainingTime);
        return;
      }

      mediaRecorderRef.current.stop();
      setIsRecording(false);
      releaseStream();
    }
  }, [releaseStream]);

  const checkSilence = useCallback((): CleanupFunction => {
    if (!mediaRecorderRef.current) return () => ({ avgAudioEnergy: 0, samplesCount: 0 });

    let audioContext: AudioContext | null = null;
    let source: MediaStreamAudioSourceNode | null = null;
    let analyser: AnalyserNode | null = null;

    try {
      audioContext = new (window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext)();
      source = audioContext.createMediaStreamSource(
        mediaRecorderRef.current.stream || new MediaStream()
      );
      analyser = audioContext.createAnalyser();
      analyser.fftSize = 256;
      source.connect(analyser);
    } catch (e) {
      console.error('Failed to create audio analysis context:', e);
      return () => ({ avgAudioEnergy: 0, samplesCount: 0 });
    }

    const silenceThreshold = 0.015; // 静音阈值（降低以减少误判）
    const initialTimeout = 5000; // 初始超时时间（5秒）
    const extensionTime = 2500; // 每次语音输入延长时间（2.5秒）
    const maxRecordingTime = 60000; // 最大录音时长（60秒）
    const minRecordingTime = 500; // 最小录音时长（0.5秒，确保有效录音）

    let timeoutDeadline = startTimeRef.current + initialTimeout; // 超时截止时间
    let totalAudioEnergy = 0; // 记录总音频能量用于全程静音检测
    let samplesCount = 0;
    let lastVoiceTime = 0; // 上次检测到语音的时间（用于防止频繁延长）

    // 实时检测（每 100ms 检测一次）
    const checkInterval = setInterval(() => {
      const currentTime = Date.now();
      const recordingDuration = currentTime - startTimeRef.current;
      const level = analyzeAudioLevel(analyser);

      // 累加音频能量用于全程静音检测
      totalAudioEnergy += level;
      samplesCount++;

      const isSilence = level < silenceThreshold;

      // 每秒输出一次音频能量信息（用于调试）
      if (samplesCount % 10 === 0) {
        console.log('[VoiceRecorder] Audio level:', {
          current: level.toFixed(4),
          avg: (totalAudioEnergy / samplesCount).toFixed(4),
          threshold: silenceThreshold,
          isSilence
        });
      }

      // 最大录音时长检查
      if (recordingDuration >= maxRecordingTime) {
        stopRecording();
        clearInterval(checkInterval);
        return;
      }

      // 检测到语音输入
      if (!isSilence) {
        // 每次检测到语音，延长超时时间（防抖：至少间隔 500ms 才延长）
        if (currentTime - lastVoiceTime > 500) {
          const newDeadline = currentTime + extensionTime;
          // 只有当新的截止时间更晚时才延长
          if (newDeadline > timeoutDeadline) {
            timeoutDeadline = newDeadline;
          }
          lastVoiceTime = currentTime;
        }
      }

      // 检查是否超时
      const remainingTime = timeoutDeadline - currentTime;
      if (remainingTime <= 0 && recordingDuration >= minRecordingTime) {
        stopRecording();
        clearInterval(checkInterval);
        return;
      }
    }, 100);

    // 返回清理函数，包含总能量信息
    return () => {
      clearInterval(checkInterval);
      if (analyser) {
        analyser.disconnect();
      }
      if (source) {
        source.disconnect();
      }
      if (audioContext) {
        audioContext.close().catch(console.warn);
      }

      // 计算平均音频能量
      const avgAudioEnergy = samplesCount > 0 ? totalAudioEnergy / samplesCount : 0;
      return { avgAudioEnergy, samplesCount };
    };
  }, [analyzeAudioLevel, stopRecording]);

  const startRecording = useCallback(async () => {
    setError(null);
    setAudioBlob(null);
    setAudioDuration(0);
    setIsRecordingReady(false);
    setIsTooShort(false);

    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      streamRef.current = stream;
      setIsRecordingReady(true);

      // 选择合适的 MIME 类型（优先 webm/opus，回退到浏览器默认）
      const mimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
        ? 'audio/webm;codecs=opus'
        : MediaRecorder.isTypeSupported('audio/webm')
          ? 'audio/webm'
          : undefined; // 使用浏览器默认

      const mediaRecorder = new MediaRecorder(stream, {
        ...(mimeType && { mimeType }),
        // 128 kbps 是语音录音的合理比特率
        audioBitsPerSecond: 128000,
      });

      mediaRecorderRef.current = mediaRecorder;
      audioChunksRef.current = [];

      mediaRecorder.ondataavailable = (event) => {
        if (event.data && event.data.size > 0) {
          audioChunksRef.current!.push(event.data);
        }
      };

      mediaRecorder.onstop = () => {
        const duration = (Date.now() - startTimeRef.current) / 1000;

        // 检查是否过短（< 0.5秒）
        if (duration < 0.5) {
          console.warn('⚠️ Recording too short, discarding');
          setIsTooShort(true);
          setAudioBlob(null);
          setAudioDuration(0);
          return;
        }

        const audioBlob = new Blob(audioChunksRef.current!, { type: mediaRecorder.mimeType || 'audio/webm' });

        // 检查音频大小是否合理（至少 5KB，否则可能是空音频）
        if (audioBlob.size < 5000) {
          console.warn('⚠️ Audio blob too small, might be empty');
          setIsTooShort(true);
          setAudioBlob(null);
          setAudioDuration(0);
          return;
        }

        setAudioBlob(audioBlob);
        setAudioDuration(duration);
      };

      // 使用 timeslice 参数，每 500ms 收集一次数据，确保增量捕获
      mediaRecorder.start(500);
      startTimeRef.current = Date.now();
      setIsRecording(true);

      // 启动静音检测
      const cleanupDetection = checkSilence();

      // 录音结束后停止检测并处理
      const originalOnStop = mediaRecorder.onstop;
      mediaRecorder.onstop = (event) => {
        const energyInfo: EnergyInfo = cleanupDetection ? cleanupDetection() as EnergyInfo : { avgAudioEnergy: 0, samplesCount: 0 };

        // 检查是否全程静音（平均能量 < 阈值）
        // 降低阈值到 0.001，避免误判正常语音为静音
        // 同时增加日志输出，方便调试
        console.log('[VoiceRecorder] Audio energy check:', {
          avgEnergy: energyInfo.avgAudioEnergy,
          samples: energyInfo.samplesCount,
          threshold: 0.001
        });

        if (energyInfo.avgAudioEnergy < 0.001 && energyInfo.samplesCount > 10) {
          console.warn('⚠️ Full silence detected, discarding');
          setIsTooShort(true);
          setAudioBlob(null);
          setAudioDuration(0);
        }

        // 调用原始的 onstop 处理
        if (originalOnStop) {
          originalOnStop.call(mediaRecorder, event);
        }

        mediaRecorder.onstop = null;
      };

    } catch (err: unknown) {
      console.error('Failed to start recording:', err);
      const errorMessage = err instanceof Error ? err.message : 'Unknown error';
      setError('Unable to access microphone: ' + errorMessage);
    }
  }, [checkSilence]);

  // 清理音频 Blob，释放内存（消费即焚策略）
  const clearAudioBlob = useCallback(() => {
    if (audioBlob) {
      setAudioBlob(null);
      setAudioDuration(0);
      // 清理 audioChunksRef，释放内存
      audioChunksRef.current = [];
    }
  }, [audioBlob]);

  return {
    isRecording,
    isRecordingReady,
    startRecording,
    stopRecording,
    audioBlob,
    audioDuration,
    error,
    isTooShort,
    clearAudioBlob,
  };
}
