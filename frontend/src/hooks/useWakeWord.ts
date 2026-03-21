import { useState, useEffect, useRef, useCallback } from 'react';

// 使用 Vite 的 ?url 语法导入模型文件，确保在 Electron 打包后路径依然有效
import porcupineModelUrl from '/porcupine_params.pv?url';
import hiLavisKeywordUrl from '/hi-lavis.ppn?url';

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
    /**
     * 解析资源路径，兼容 Electron 和 Web 环境
     * 优先使用 Vite 导入的 URL，确保打包后路径正确
     */
    const resolvePublicPath = (publicPath: string, importedUrl?: string): string => {
      // 已经是完整 URL，直接返回
      if (
        publicPath.startsWith('http://') ||
        publicPath.startsWith('https://') ||
        publicPath.startsWith('file://') ||
        publicPath.startsWith('data:')
      ) {
        return publicPath;
      }

      // Electron file 协议处理 - 需要指向 app.asar.unpacked 目录
      if (window.location.protocol === 'file:') {
        // window.location.href 类似: file:///path/to/app.asar/dist/index.html
        // 需要转换为: file:///path/to/app.asar.unpacked/dist/hi-lavis.ppn
        const currentUrl = window.location.href;
        const asarMatch = currentUrl.match(/^(file:\/\/.*?)(\/[^/]+\.asar)(\/.*)/);

        if (asarMatch) {
          // 在 asar 包内，使用 .unpacked 目录
          const [, prefix, asarPath, ] = asarMatch;
          const normalized = publicPath.replace(/^\//, '');
          const resolved = `${prefix}${asarPath}.unpacked/dist/${normalized}`;
          return resolved;
        }

        // 非 asar 环境（开发模式），使用相对路径
        const normalized = publicPath.replace(/^\//, '');
        const resolved = new URL(normalized, window.location.href).toString();
        return resolved;
      }

      // Web 环境：如果有 Vite 导入的 URL，使用它；否则使用 publicPath
      if (importedUrl) {
        return importedUrl;
      }

      return publicPath;
    };

    // 如果没有 Access Key，报错并停止
    if (!accessKey) {
      console.error('[Porcupine] Missing VITE_PICOVOICE_KEY');
      setError('Picovoice Access Key is not configured');
      setIsListening(false);
      return;
    }

    // 验证 Access Key 格式（Picovoice Access Key 通常是 32 字符的字符串）
    const trimmedKey = accessKey.trim();
    if (trimmedKey.length < 20) {
      console.error('[Porcupine] Access Key format invalid');
      setError('Access Key format is invalid');
      setIsListening(false);
      return;
    }

    // 检查是否包含明显的无效字符
    if (trimmedKey.includes('your_access_key') || trimmedKey.includes('YOUR_ACCESS_KEY')) {
      console.error('[Porcupine] Placeholder Access Key detected');
      setError('Please configure a real Access Key');
      setIsListening(false);
      return;
    }

    try {
      // 动态导入 Porcupine 和 WebVoiceProcessor
      const [{ PorcupineWorker }, { WebVoiceProcessor }] = await Promise.all([
        import('@picovoice/porcupine-web'),
        import('@picovoice/web-voice-processor')
      ]);
      
      // 保存 WebVoiceProcessor 引用
      webVoiceProcessorRef.current = WebVoiceProcessor;

      // Porcupine v4 API 需要以下参数:
      // 1. accessKey - Picovoice Access Key
      // 2. keywords - 唤醒词配置（内置或自定义）
      // 3. keywordDetectionCallback - 检测回调
      // 4. model - Porcupine 基础模型（必需）
      
      // 配置唤醒词 - 优先使用 Vite 导入的 URL，其次 publicPath，再次 base64，最后使用内置词
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      let keywords: any[];
      let wakeWordLabel: string;

      if (keywordPath || hiLavisKeywordUrl) {
        // 优先使用 publicPath（推荐做法，避免错误的 Base64 配置导致初始化失败）
        const resolvedKeywordPath = resolvePublicPath(
          keywordPath || '/hi-lavis.ppn',
          keywordPath ? undefined : hiLavisKeywordUrl // 如果没有自定义路径，使用 Vite 导入的 URL
        );
        wakeWordLabel = '"Hi Lavis" (via publicPath)';
        keywords = [{
          label: 'Hi Lavis',
          publicPath: resolvedKeywordPath,
          sensitivity: 0.7,
        }];
      } else if (keywordBase64) {
        // Base64 作为兜底方案（防止错误的 Base64 阻塞正常文件路径）
        wakeWordLabel = '"Hi Lavis" (via base64)';
        keywords = [{
          label: 'Hi Lavis',
          base64: keywordBase64,
          sensitivity: 0.7,
        }];
      } else {
        // 使用内置关键词（fallback）
        wakeWordLabel = '"Porcupine" (built-in)';
        keywords = [{
          builtin: 'Porcupine' as const,
          sensitivity: 0.5,
        }];
      }

      console.log(`[Porcupine] Wake word configured: ${wakeWordLabel}`);

      // 检测回调
      const detectionCallback = () => {
        onWakeRef.current?.();
      };

      // Porcupine 基础模型（使用 Vite 导入的 URL）
      const modelPublicPath = resolvePublicPath('/porcupine_params.pv', porcupineModelUrl);
      const model = { publicPath: modelPublicPath };

      // 创建 Porcupine Worker (v4 API)
      const porcupine = await PorcupineWorker.create(
        accessKey,
        keywords,
        detectionCallback,
        model
      );

      porcupineRef.current = porcupine;
      
      // 使用 WebVoiceProcessor 订阅 Porcupine 引擎（而不是直接调用 porcupine.start()）
      await WebVoiceProcessor.subscribe(porcupine);
      
      setIsListening(true);
      setError(null);

    } catch (err: unknown) {
      console.error('[Porcupine] Failed to initialize:', err);

      let errorMessage = 'Unknown error';
      let errorDetails = '';
      
      if (err instanceof Error) {
        errorMessage = err.message;
        const errorName = err.name || '';
        const errorString = err.toString();

        // 检查是否是激活错误（PorcupineActivationRefusedError）
        if (
          errorName.includes('Activation') ||
          errorName.includes('ActivationRefused') ||
          errorMessage.includes('Activation') ||
          errorMessage.includes('Initialization failed') ||
          errorString.includes('Activation')
        ) {
          errorMessage = 'Porcupine activation failed';
          errorDetails = `
Activation errors are usually caused by:
1. Invalid or expired Access Key
2. Access Key not authorized for Web/WASM platform
3. Network issues reaching Picovoice servers
4. Access Key usage limit reached

Please check:
- Visit https://console.picovoice.ai/ to verify Access Key status
- Ensure Web platform permission is enabled for the key
- Confirm your network connection is healthy
- If using the free tier, verify usage limits are not exceeded

Current Access Key: ${accessKey ? accessKey.slice(0, 15) + '...' : 'not configured'}
          `.trim();
          console.error('[Porcupine] Activation error:', errorDetails);
        } else if (errorMessage.includes('Invalid AccessKey') || errorMessage.includes('Invalid access key')) {
          errorMessage = 'Picovoice Access Key is invalid';
          errorDetails = 'Please check VITE_PICOVOICE_KEY in your .env.local file.';
        } else if (errorMessage.includes('microphone') || errorMessage.includes('Microphone')) {
          errorMessage = 'Unable to access microphone';
          errorDetails = 'Please grant microphone permission and refresh the page.';
        } else if (errorMessage.includes('model') || errorMessage.includes('Model')) {
          errorMessage = 'Failed to load Porcupine model';
          errorDetails = 'Please verify /public/porcupine_params.pv exists and is accessible.';
        } else if (errorMessage.includes('platform') || errorMessage.includes('format') || errorMessage.includes('Platform')) {
          errorMessage = 'Wake word model format is invalid';
          errorDetails = 'Please use a .ppn file built for the Web (WASM) platform.';
        } else if (errorMessage.includes('File not found') || errorMessage.includes('Network error') || errorMessage.includes('404')) {
          errorMessage = 'Failed to load model file';
          errorDetails = `Path resolution issue:
- Model path: ${porcupineModelUrl}
- Wake word path: ${hiLavisKeywordUrl}
Please ensure files exist under public/ and Vite build config is correct.`;
        } else if (errorMessage.includes('network') || errorMessage.includes('Network') || errorMessage.includes('fetch')) {
          errorMessage = 'Network connection failed';
          errorDetails = 'Unable to reach Picovoice servers. Please check your network.';
        }
      }

      setError(errorMessage + (errorDetails ? `\n${errorDetails}` : ''));
      setIsListening(false);
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
      } catch (err) {
        console.error('[Porcupine] Error stopping:', err);
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
