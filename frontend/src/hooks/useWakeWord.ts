import { useState, useEffect, useRef, useCallback } from 'react';

/**
 * Wake Word Hook 配置参数
 */
interface UseWakeWordProps {
  /** Picovoice Access Key (从 https://console.picovoice.ai/ 获取) */
  accessKey?: string;
  /** 自定义唤醒词文件的 publicPath (如 '/hi-lavis.ppn')，或 Base64 编码 */
  keywordPath?: string;
  /** 自定义唤醒词的 Base64 编码 (可选，优先使用 keywordPath) */
  keywordBase64?: string;
  /** 唤醒时的回调函数 */
  onWake?: () => void;
  /** 是否启用监听 */
  enabled?: boolean;
}

/**
 * Wake Word Hook 返回值
 */
interface UseWakeWordReturn {
  /** 是否正在监听唤醒词 */
  isListening: boolean;
  /** 错误信息 */
  error: string | null;
  /** 手动开始监听 */
  startListening: () => void;
  /** 手动停止监听 */
  stopListening: () => void;
}

// PorcupineWorker 实例类型定义（不包含 start/stop，因为这些由 WebVoiceProcessor 管理）
interface PorcupineWorkerInstance {
  release: () => Promise<void>;
}

// WebVoiceProcessor 类型定义
interface WebVoiceProcessorType {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  subscribe: (engine: any) => Promise<void>;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  unsubscribe: (engine: any) => Promise<void>;
}

/**
 * 唤醒词检测 Hook
 * 
 * 使用 Picovoice Porcupine v4 进行离线唤醒词检测
 * 
 * 使用方法:
 * 1. 在 https://console.picovoice.ai/ 注册并获取 Access Key
 * 2. 在 .env 文件中设置 VITE_PICOVOICE_KEY=your_access_key
 * 3. (可选) 训练自定义唤醒词 "Hi Lavis" 并下载 .ppn 文件，转为 Base64
 * 4. 在 .env 中设置 VITE_WAKE_WORD_BASE64=<base64 string>
 * 
 * @example
 * ```tsx
 * const { isListening, error } = useWakeWord({
 *   accessKey: import.meta.env.VITE_PICOVOICE_KEY,
 *   keywordBase64: import.meta.env.VITE_WAKE_WORD_BASE64,
 *   onWake: () => {
 *     console.log('Wake word detected!');
 *     startRecording();
 *   }
 * });
 * ```
 */
export function useWakeWord({ 
  accessKey, 
  keywordPath,
  keywordBase64,
  onWake, 
  enabled = true 
}: UseWakeWordProps): UseWakeWordReturn {
  const [isListening, setIsListening] = useState(false);
  const [error, setError] = useState<string | null>(null);
  
  // 使用 ref 存储 Porcupine 实例，避免重复创建
  const porcupineRef = useRef<PorcupineWorkerInstance | null>(null);
  const webVoiceProcessorRef = useRef<WebVoiceProcessorType | null>(null);
  const onWakeRef = useRef(onWake);
  
  // 保持 onWake 回调的最新引用
  useEffect(() => {
    onWakeRef.current = onWake;
  }, [onWake]);

  /**
   * 初始化 Porcupine
   */
  const initPorcupine = useCallback(async () => {
    const resolvePublicPath = (publicPath: string): string => {
      if (
        publicPath.startsWith('http://') ||
        publicPath.startsWith('https://') ||
        publicPath.startsWith('file://') ||
        publicPath.startsWith('data:')
      ) {
        return publicPath;
      }

      if (window.location.protocol === 'file:') {
        const normalized = publicPath.replace(/^\//, '');
        return new URL(normalized, window.location.href).toString();
      }

      return publicPath;
    };
    // 调试：打印环境变量状态
    console.log('🎤 Wake word config check:');
    console.log(`   - Access Key: ${accessKey ? '✅ 已配置 (' + accessKey.slice(0, 10) + '...)' : '❌ 未配置'}`);
    console.log(`   - Keyword Path: ${keywordPath ? '✅ ' + keywordPath : '❌ 未配置'}`);
    console.log(`   - Keyword Base64: ${keywordBase64 ? '✅ 已配置' : '❌ 未配置'}`);

    // 如果没有 Access Key，报错并停止
    if (!accessKey) {
      const errorMsg = '⚠️ 缺少 VITE_PICOVOICE_KEY 环境变量，无法启动唤醒词检测';
      console.error(errorMsg);
      console.log('   请在 .env.local 文件中配置：');
      console.log('   VITE_PICOVOICE_KEY=你的AccessKey');
      setError('未配置 Picovoice Access Key');
      setIsListening(false);
      return;
    }

    try {
      // 动态导入 Porcupine 和 WebVoiceProcessor
      console.log('🎤 Loading Porcupine v4 and WebVoiceProcessor modules...');
      const [{ PorcupineWorker }, { WebVoiceProcessor }] = await Promise.all([
        import('@picovoice/porcupine-web'),
        import('@picovoice/web-voice-processor')
      ]);
      
      // 保存 WebVoiceProcessor 引用
      webVoiceProcessorRef.current = WebVoiceProcessor;
      
      console.log('🎤 Initializing Porcupine v4 wake word detection...');

      // Porcupine v4 API 需要以下参数:
      // 1. accessKey - Picovoice Access Key
      // 2. keywords - 唤醒词配置（内置或自定义）
      // 3. keywordDetectionCallback - 检测回调
      // 4. model - Porcupine 基础模型（必需）
      
      // 配置唤醒词 - 优先使用 publicPath，其次 base64，最后使用内置词
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      let keywords: any[];
      let wakeWordLabel: string;

      if (keywordPath) {
        // 使用 publicPath 加载 .ppn 文件（推荐方式）
        const resolvedKeywordPath = resolvePublicPath(keywordPath);
        wakeWordLabel = '"Hi Lavis" (via publicPath)';
        keywords = [{
          label: 'Hi Lavis',
          publicPath: resolvedKeywordPath,
          sensitivity: 0.7,
        }];
        console.log(`   Keyword: ${wakeWordLabel}`);
        console.log(`   Loading from: ${resolvedKeywordPath}`);
      } else if (keywordBase64) {
        // 使用 Base64 加载
        wakeWordLabel = '"Hi Lavis" (via base64)';
        keywords = [{
          label: 'Hi Lavis',
          base64: keywordBase64,
          sensitivity: 0.7,
        }];
        console.log(`   Keyword: ${wakeWordLabel}`);
      } else {
        // 使用内置关键词
        wakeWordLabel = '"Porcupine" (内置)';
        keywords = [{
          builtin: 'Porcupine' as const,
          sensitivity: 0.5,
        }];
        console.log(`   Keyword: ${wakeWordLabel}`);
      }

      // 检测回调
      const detectionCallback = (detection: { index: number; label: string }) => {
        console.log(`🎉 Wake word detected: "${detection.label}" (index: ${detection.index})`);
        onWakeRef.current?.();
      };

      // Porcupine 基础模型（从 public 目录加载）
      const modelPublicPath = resolvePublicPath('/porcupine_params.pv');
      const model = { publicPath: modelPublicPath };
      console.log(`   Model: ${modelPublicPath}`);

      // 创建 Porcupine Worker (v4 API)
      console.log('🎤 Creating PorcupineWorker...');
      const porcupine = await PorcupineWorker.create(
        accessKey,
        keywords,
        detectionCallback,
        model
      );

      porcupineRef.current = porcupine;
      
      // 使用 WebVoiceProcessor 订阅 Porcupine 引擎（而不是直接调用 porcupine.start()）
      console.log('🎤 Starting audio capture via WebVoiceProcessor...');
      await WebVoiceProcessor.subscribe(porcupine);
      
      setIsListening(true);
      setError(null);
      console.log('✅ Porcupine wake word detection started successfully!');
      console.log(`   Now listening for: ${wakeWordLabel}`);

    } catch (err: unknown) {
      console.error('❌ Failed to initialize Porcupine:', err);
      
      let errorMessage = 'Unknown error';
      if (err instanceof Error) {
        errorMessage = err.message;
        
        // 提供更友好的错误提示
        if (errorMessage.includes('Invalid AccessKey')) {
          errorMessage = 'Picovoice Access Key 无效，请检查配置';
        } else if (errorMessage.includes('microphone')) {
          errorMessage = '无法访问麦克风，请授予权限';
        } else if (errorMessage.includes('model')) {
          errorMessage = 'Porcupine 模型加载失败，请检查 /public/porcupine_params.pv';
        } else if (errorMessage.includes('platform') || errorMessage.includes('format')) {
          errorMessage = '唤醒词模型格式错误，请确保使用 Web (WASM) 平台的 .ppn 文件';
        }
      }
      
      setError(errorMessage);
      setIsListening(false);
      
      // 不降级，让用户知道问题
      console.log('⚠️ Wake word detection failed, please check configuration');
    }
  }, [accessKey, keywordPath, keywordBase64]);

  /**
   * 停止 Porcupine
   */
  const stopPorcupine = useCallback(async () => {
    if (porcupineRef.current) {
      try {
        // 使用 WebVoiceProcessor 取消订阅（而不是直接调用 porcupine.stop()）
        if (webVoiceProcessorRef.current) {
          await webVoiceProcessorRef.current.unsubscribe(porcupineRef.current);
        }
        await porcupineRef.current.release();
        console.log('🎤 Porcupine stopped');
      } catch (err) {
        console.warn('Error stopping Porcupine:', err);
      }
      porcupineRef.current = null;
    }
    setIsListening(false);
  }, []);

  /**
   * 手动开始监听
   */
  const startListening = useCallback(() => {
    if (!isListening && enabled) {
      initPorcupine();
    }
  }, [isListening, enabled, initPorcupine]);

  /**
   * 手动停止监听
   */
  const stopListening = useCallback(() => {
    stopPorcupine();
  }, [stopPorcupine]);

  // 根据 enabled 状态自动启动/停止
  useEffect(() => {
    if (enabled && !isListening) {
      initPorcupine();
    } else if (!enabled && isListening) {
      stopPorcupine();
    }
    
    // Cleanup
    return () => {
      stopPorcupine();
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled]);

  return { 
    isListening, 
    error, 
    startListening, 
    stopListening 
  };
}
