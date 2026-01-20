import { useState, useRef, useCallback } from 'react';

/**
 * Voice Recorder Hook
 *
 * 使用 MediaRecorder API 进行录音，支持智能静音检测（VAD）
 *
 * 核心算法：
 * 1. 5秒保护期：录音开始的前5秒强制录音，不停止
 * 2. 动态静音截断：5秒后如果静音超过3秒则停止录音
 * 3. 最大录音时长：60秒自动停止
 * 4. 全程静音检测：低能量音频自动丢弃
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

    const dataArray = new Float32Array(analyser.fftSize);
    analyser.getFloatFrequencyData(dataArray);

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
      streamRef.current.getTracks().forEach(track => {
        track.stop();
        console.log('🔇 Audio track stopped');
      });
      streamRef.current = null;
    }
  }, []);

  const stopRecording = useCallback(() => {
    if (mediaRecorderRef.current && mediaRecorderRef.current.state === 'recording') {
      const duration = (Date.now() - startTimeRef.current) / 1000;
      
      // 强制最少录音 1 秒
      if (duration < 1.0) {
        console.log(`⏳ Recording too short (${duration.toFixed(2)}s), waiting for minimum 1s...`);
        // 延迟停止，确保至少 1 秒
        const remainingTime = (1.0 - duration) * 1000;
        setTimeout(() => {
          if (mediaRecorderRef.current && mediaRecorderRef.current.state === 'recording') {
            mediaRecorderRef.current.stop();
            setIsRecording(false);
            releaseStream();
            console.log('Recording stopped (after minimum time)');
          }
        }, remainingTime);
        return;
      }
      
      mediaRecorderRef.current.stop();
      setIsRecording(false);
      releaseStream();
      console.log(`Recording stopped (${duration.toFixed(2)}s)`);
    }
  }, [releaseStream]);

  const checkSilence = useCallback((): CleanupFunction => {
    if (!mediaRecorderRef.current) return () => ({ avgAudioEnergy: 0, samplesCount: 0 });

    const audioContext = new (window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext)();
    const source = audioContext.createMediaStreamSource(
      mediaRecorderRef.current.stream || new MediaStream()
    );
    const analyser = audioContext.createAnalyser();
    analyser.fftSize = 256;
    source.connect(analyser);

    const silenceThreshold = 0.02; // 静音阈值
    const minRecordingTime = 5000; // 最小录音保护期（5秒）
    const maxSilenceTime = 3000; // 最大静音时长（3秒）
    const maxRecordingTime = 60000; // 最大录音时长（60秒）

    let silenceStartTime: number | null = null;
    let totalAudioEnergy = 0; // 记录总音频能量用于全程静音检测
    let samplesCount = 0;

    // 实时检测（每 100ms 检测一次）
    const checkInterval = setInterval(() => {
      const currentTime = Date.now();
      const recordingDuration = currentTime - startTimeRef.current;
      const level = analyzeAudioLevel(analyser);

      // 累加音频能量用于全程静音检测
      totalAudioEnergy += level;
      samplesCount++;

      const isSilence = level < silenceThreshold;

      // 最大录音时长检查
      if (recordingDuration >= maxRecordingTime) {
        console.log('🛑 Max recording time reached (60s), stopping...');
        stopRecording();
        clearInterval(checkInterval);
        return;
      }

      // 最小录音保护期：5秒内强制录音，不停止
      if (recordingDuration < minRecordingTime) {
        // 保护期内忽略截断逻辑，重置静音计时
        silenceStartTime = null;
        return;
      }

      // 5秒后：检测静音时长
      if (isSilence) {
        if (!silenceStartTime) {
          silenceStartTime = currentTime;
          console.log('⏸️ Silence detected, starting silence timer...');
        }

        const silenceDuration = currentTime - silenceStartTime;
        if (silenceDuration >= maxSilenceTime) {
          console.log(`🛑 Silence for ${silenceDuration}ms, stopping recording...`);
          stopRecording();
          clearInterval(checkInterval);
        }
      } else {
        // 检测到语音，重置静音计时
        if (silenceStartTime) {
          console.log('🗣️ Voice detected, resetting silence timer');
        }
        silenceStartTime = null;
      }

      // 实时音频级别日志（每秒一次）
      if (samplesCount % 10 === 0) {
        console.log(`🎤 Audio level: ${level.toFixed(4)} | Duration: ${(recordingDuration / 1000).toFixed(1)}s | Silence: ${silenceStartTime ? ((currentTime - silenceStartTime) / 1000).toFixed(1) + 's' : '0s'}`);
      }
    }, 100);

    // 返回清理函数，包含总能量信息
    return () => {
      clearInterval(checkInterval);
      analyser.disconnect();
      source.disconnect();
      audioContext.close();

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
      console.log('🎤 Microphone stream acquired, recorder ready');

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

      console.log(`🎙️ MediaRecorder created with mimeType: ${mediaRecorder.mimeType}`);

      mediaRecorderRef.current = mediaRecorder;
      audioChunksRef.current = [];

      mediaRecorder.ondataavailable = (event) => {
        if (event.data && event.data.size > 0) {
          audioChunksRef.current!.push(event.data);
          console.log(`📦 Audio chunk received: ${event.data.size} bytes`);
        }
      };

      mediaRecorder.onstop = () => {
        const duration = (Date.now() - startTimeRef.current) / 1000;
        console.log(`📼 Recording completed: ${duration.toFixed(2)}s`);

        // 检查是否过短（< 1秒）
        if (duration < 1.0) {
          console.warn('⚠️ Recording too short (< 1.0s), discarding...');
          setIsTooShort(true);
          setAudioBlob(null);
          setAudioDuration(0);
          return;
        }

        const audioBlob = new Blob(audioChunksRef.current!, { type: mediaRecorder.mimeType || 'audio/webm' });
        console.log(`📀 Audio blob created: ${audioBlob.size} bytes, type: ${audioBlob.type}`);
        
        // 检查音频大小是否合理（至少 5KB，否则可能是空音频）
        if (audioBlob.size < 5000) {
          console.warn(`⚠️ Audio blob too small (${audioBlob.size} bytes), might be empty`);
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
      console.log('Recording started (5s protection period)');

      // 启动静音检测
      const cleanupDetection = checkSilence();

      // 录音结束后停止检测并处理
      const originalOnStop = mediaRecorder.onstop;
      mediaRecorder.onstop = (event) => {
        const energyInfo: EnergyInfo = cleanupDetection ? cleanupDetection() as EnergyInfo : { avgAudioEnergy: 0, samplesCount: 0 };

        console.log(`📊 Audio analysis: avgEnergy=${energyInfo.avgAudioEnergy.toFixed(4)}, samples=${energyInfo.samplesCount}`);

        // 检查是否全程静音（平均能量 < 阈值）
        if (energyInfo.avgAudioEnergy < 0.01 && energyInfo.samplesCount > 10) {
          console.warn('⚠️ Full silence detected, discarding recording...');
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
      setError('无法访问麦克风: ' + errorMessage);
    }
  }, [checkSilence]);

  return {
    isRecording,
    isRecordingReady,
    startRecording,
    stopRecording,
    audioBlob,
    audioDuration,
    error,
    isTooShort,
  };
}