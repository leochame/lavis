import { useState, useEffect, useRef, useCallback } from 'react';

/**
 * Vosk Wake Word Hook 配置参数
 */
interface UseVoskWakeWordProps {
  /** 唤醒词（中文，如 "你好拉维斯"） */
  wakeWord?: string;
  /** Vosk 模型路径（.tar.gz 文件，相对于 public 目录） */
  modelPath?: string;
  /** 唤醒时的回调函数 */
  onWake?: () => void;
  /** 是否启用监听 */
  enabled?: boolean;
}

/**
 * 音近词映射表 - 处理 Vosk 模型对不常见词的误识别
 * 基于实际测试结果，将常见的误识别映射到正确的词
 * 
 * 重要：这个映射表是双向的，既可以从目标词查找误识别，也可以从误识别查找目标词
 */
const PHONETIC_SIMILAR_WORDS: Record<string, string[]> = {
  // "hi" 的常见误识别
  'hi': ['he', 'hey', 'high', 'hai', 'hello', 'her'],
  // "lavis" 的常见误识别（因为不在模型词汇表中）
  'lavis': [
    'lay reese', 'levies', 'laves', 'lavish', 'lavees', 'lave is', 'lay rees',
    'is', 'louis', 'levis', 'lobbies', 'lovelies', 'lois'
  ],
  // "hi lavis" 完整短语的常见误识别（基于实际日志观察）
  'hi lavis': [
    'he lay reese', 'hey levies', 'he lay rees', 'hey lay reese',
    'hello is',        // 实际观察到的误识别
    'hi lobbies',      // 实际观察到的误识别
    'her levis',       // 实际观察到的误识别
    'hi louis',        // 实际观察到的误识别
    'hi lovelies',     // 实际观察到的误识别
    'hello lois',      // 实际观察到的误识别
    'her lobbies',     // 实际观察到的误识别
    'calories',        // 实际观察到的误识别
    'i love',          // 部分识别
    'i live is',       // 部分识别
  ],
};

/**
 * Soundex 算法 - 用于音素级别的相似度比较
 * 将单词转换为音素代码，发音相似的词会有相同的代码
 * 
 * Soundex 规则：
 * 1. 保留第一个字母
 * 2. 将后续字母转换为数字：
 *    - B, F, P, V → 1
 *    - C, G, J, K, Q, S, X, Z → 2
 *    - D, T → 3
 *    - L → 4
 *    - M, N → 5
 *    - R → 6
 * 3. 移除连续的相同数字
 * 4. 移除所有元音（A, E, I, O, U, Y）
 * 5. 保留前4个字符，不足补0
 */
function soundex(word: string): string {
  if (!word) return '';
  
  const upper = word.toUpperCase();
  let code = upper[0]; // 保留第一个字母
  
  const mapping: Record<string, string> = {
    'B': '1', 'F': '1', 'P': '1', 'V': '1',
    'C': '2', 'G': '2', 'J': '2', 'K': '2', 'Q': '2', 'S': '2', 'X': '2', 'Z': '2',
    'D': '3', 'T': '3',
    'L': '4',
    'M': '5', 'N': '5',
    'R': '6'
  };
  
  // 转换后续字母
  for (let i = 1; i < upper.length; i++) {
    const char = upper[i];
    if (mapping[char]) {
      code += mapping[char];
    }
  }
  
  // 移除连续的相同数字
  let result = code[0];
  for (let i = 1; i < code.length; i++) {
    if (code[i] !== code[i - 1]) {
      result += code[i];
    }
  }
  
  // 保留前4个字符，不足补0
  result = result.padEnd(4, '0').substring(0, 4);
  
  return result;
}

/**
 * 计算两个字符串的编辑距离（Levenshtein distance）
 * 用于模糊匹配
 */
function levenshteinDistance(str1: string, str2: string): number {
  const m = str1.length;
  const n = str2.length;
  const dp: number[][] = Array(m + 1).fill(null).map(() => Array(n + 1).fill(0));

  for (let i = 0; i <= m; i++) dp[i][0] = i;
  for (let j = 0; j <= n; j++) dp[0][j] = j;

  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= n; j++) {
      if (str1[i - 1] === str2[j - 1]) {
        dp[i][j] = dp[i - 1][j - 1];
      } else {
        dp[i][j] = Math.min(
          dp[i - 1][j] + 1,     // deletion
          dp[i][j - 1] + 1,     // insertion
          dp[i - 1][j - 1] + 1  // substitution
        );
      }
    }
  }

  return dp[m][n];
}

/**
 * 计算两个字符串的相似度分数（0-1之间，1表示完全相同）
 */
function similarityScore(str1: string, str2: string): number {
  const maxLen = Math.max(str1.length, str2.length);
  if (maxLen === 0) return 1;
  const distance = levenshteinDistance(str1, str2);
  return 1 - (distance / maxLen);
}

/**
 * 检查一个词是否与目标词音近匹配
 * 使用多级匹配策略：完全匹配 → 映射表 → Soundex → 编辑距离
 */
function isPhoneticallySimilar(word: string, target: string): boolean {
  const normalizedWord = word.toLowerCase().trim();
  const normalizedTarget = target.toLowerCase().trim();

  // 1. 完全匹配
  if (normalizedWord === normalizedTarget) return true;

  // 2. 检查音近词映射表（正向）
  const similarWords = PHONETIC_SIMILAR_WORDS[normalizedTarget] || [];
  if (similarWords.includes(normalizedWord)) return true;

  // 3. 检查反向映射（如果识别结果是目标词的音近词）
  for (const [key, values] of Object.entries(PHONETIC_SIMILAR_WORDS)) {
    if (values.includes(normalizedWord) && key === normalizedTarget) return true;
  }

  // 4. Soundex 音素匹配（对于短词更有效）
  // 移除空格后比较 Soundex 代码
  const wordSoundex = soundex(normalizedWord.replace(/\s+/g, ''));
  const targetSoundex = soundex(normalizedTarget.replace(/\s+/g, ''));
  if (wordSoundex && targetSoundex && wordSoundex === targetSoundex) {
    return true;
  }
  
  // 5. Soundex 部分匹配（前3个字符相同）
  if (wordSoundex.length >= 3 && targetSoundex.length >= 3) {
    if (wordSoundex.substring(0, 3) === targetSoundex.substring(0, 3)) {
      return true;
    }
  }

  // 6. 使用编辑距离进行模糊匹配（允许 40% 的差异，比之前更宽松）
  const maxDistance = Math.max(1, Math.floor(normalizedTarget.length * 0.4));
  const distance = levenshteinDistance(normalizedWord, normalizedTarget);
  if (distance <= maxDistance && normalizedWord.length > 0) {
    return true;
  }

  // 7. 相似度分数匹配（如果相似度 > 60%）
  const similarity = similarityScore(normalizedWord, normalizedTarget);
  if (similarity > 0.6) {
    return true;
  }

  return false;
}

/**
 * 词级匹配：检查识别结果的每个词是否与唤醒词的对应词匹配或音近匹配
 * 允许词序略有不同（例如 "lay reese" 匹配 "lavis"）
 * 
 * 改进策略：
 * 1. 支持词序无关匹配
 * 2. 支持部分匹配（识别结果可能包含额外词）
 * 3. 使用更宽松的匹配阈值
 */
function wordLevelMatch(recognizedText: string, wakeWord: string): boolean {
  const normalizeText = (str: string) => 
    str.toLowerCase().replace(/\s+/g, ' ').trim();
  
  const normalizedText = normalizeText(recognizedText);
  const normalizedWakeWord = normalizeText(wakeWord);
  
  const wakeWordParts = normalizedWakeWord.split(/\s+/).filter(w => w.length > 0);
  const textParts = normalizedText.split(/\s+/).filter(w => w.length > 0);
  
  // 如果唤醒词只有一个词，检查识别结果中是否有音近词
  if (wakeWordParts.length === 1) {
    const targetWord = wakeWordParts[0];
    // 检查是否完全包含或音近匹配
    if (normalizedText === targetWord || normalizedText.includes(targetWord)) {
      return true;
    }
    // 检查每个识别词是否音近匹配
    for (const textPart of textParts) {
      if (isPhoneticallySimilar(textPart, targetWord)) {
        return true;
      }
    }
    // 检查组合词（例如 "lay reese" 匹配 "lavis"）
    for (let i = 0; i < textParts.length; i++) {
      for (let j = i + 1; j <= textParts.length; j++) {
        const combined = textParts.slice(i, j).join(' ');
        if (isPhoneticallySimilar(combined, targetWord)) {
          return true;
        }
      }
    }
    return false;
  }
  
  // 多词匹配：检查是否所有唤醒词都能在识别结果中找到匹配
  // 使用更灵活的匹配策略，允许词序不同
  let matchedCount = 0;
  const usedIndices = new Set<number>();
  
  for (const wakeWordPart of wakeWordParts) {
    let found = false;
    
    // 策略1: 完全匹配（单个词）
    for (let i = 0; i < textParts.length; i++) {
      if (!usedIndices.has(i) && textParts[i] === wakeWordPart) {
        usedIndices.add(i);
        found = true;
        matchedCount++;
        break;
      }
    }
    
    // 策略2: 音近匹配（单个词）
    if (!found) {
      for (let i = 0; i < textParts.length; i++) {
        if (!usedIndices.has(i) && isPhoneticallySimilar(textParts[i], wakeWordPart)) {
          usedIndices.add(i);
          found = true;
          matchedCount++;
          break;
        }
      }
    }
    
    // 策略3: 组合词匹配（例如 "lay reese" 匹配 "lavis"）
    // 允许匹配连续的多个词
    if (!found) {
      for (let i = 0; i < textParts.length; i++) {
        for (let j = i + 1; j <= textParts.length && j <= i + 3; j++) { // 最多匹配3个连续词
          // 检查这些索引是否已被使用
          let allAvailable = true;
          for (let k = i; k < j; k++) {
            if (usedIndices.has(k)) {
              allAvailable = false;
              break;
            }
          }
          if (!allAvailable) continue;
          
          const combined = textParts.slice(i, j).join(' ');
          if (isPhoneticallySimilar(combined, wakeWordPart)) {
            // 标记所有使用的索引
            for (let k = i; k < j; k++) {
              usedIndices.add(k);
            }
            found = true;
            matchedCount++;
            break;
          }
        }
        if (found) break;
      }
    }
    
    // 策略4: 部分匹配（如果唤醒词的一部分在识别结果中）
    // 例如 "lavis" 可能被识别为 "is"，检查是否包含关键音素
    if (!found && wakeWordPart.length > 3) {
      // 提取关键音素（Soundex 的前2个字符）
      const targetSoundex = soundex(wakeWordPart);
      for (let i = 0; i < textParts.length; i++) {
        if (!usedIndices.has(i)) {
          const textSoundex = soundex(textParts[i]);
          // 如果 Soundex 前2个字符匹配，认为可能匹配
          if (targetSoundex.length >= 2 && textSoundex.length >= 2) {
            if (targetSoundex.substring(0, 2) === textSoundex.substring(0, 2)) {
              // 进一步检查相似度
              const similarity = similarityScore(textParts[i], wakeWordPart);
              if (similarity > 0.5) { // 50% 相似度阈值
                usedIndices.add(i);
                found = true;
                matchedCount++;
                break;
              }
            }
          }
        }
      }
    }
  }
  
  // 如果匹配了至少 70% 的词，认为匹配成功（从80%降低到70%，更宽松）
  const matchRatio = matchedCount / wakeWordParts.length;
  return matchRatio >= 0.7;
}

/**
 * Vosk Wake Word Hook 返回值
 */
interface UseVoskWakeWordReturn {
  /** 是否正在监听唤醒词 */
  isListening: boolean;
  /** 模型是否已加载 */
  isModelLoaded: boolean;
  /** 错误信息 */
  error: string | null;
  /** 手动开始监听 */
  startListening: () => void;
  /** 手动停止监听 */
  stopListening: () => void;
}

// Vosk 类型定义 - 基于 vosk-browser 的实际 API
interface VoskRecognizer {
  id: string;
  setWords: (words: boolean) => void;
  on: (event: 'partialresult' | 'result' | 'error', callback: (message: VoskResultMessage) => void) => void;
  acceptWaveform: (buffer: AudioBuffer) => void;
  acceptWaveformFloat: (buffer: Float32Array, sampleRate: number) => void;
  retrieveFinalResult: () => void;
  remove: () => void;
}

interface VoskResultMessage {
  event: 'partialresult' | 'result' | 'error';
  recognizerId: string;
  result?: {
    text?: string;
    partial?: string;
    result?: Array<{
      conf: number;
      start: number;
      end: number;
      word: string;
    }>;
  };
  error?: string;
}

interface VoskModel {
  KaldiRecognizer: new (sampleRate: number, grammar?: string) => VoskRecognizer;
  setLogLevel: (level: number) => void;
  terminate: () => void;
}

interface VoskModule {
  createModel: (modelPath: string, logLevel?: number) => Promise<VoskModel>;
}

/**
 * Vosk 离线唤醒词检测 Hook
 *
 * 使用 vosk-browser (WASM) 进行完全离线的语音唤醒检测
 * 作为 Picovoice Porcupine 的开源替代方案
 *
 * 特点:
 * - 完全离线，无需网络
 * - 免费开源
 * - 支持中文唤醒词
 * - 使用 Grammar 限制识别范围，降低 CPU 占用
 *
 * 使用前准备:
 * 1. 下载 Vosk 中文模型: https://alphacephei.com/vosk/models
 *    推荐: vosk-model-small-cn-0.22 (~40MB)
 * 2. 将模型打包为 .tar.gz 格式放到 public/models/ 目录
 *    或直接下载 vosk-model-small-cn-0.22.tar.gz
 *
 * @example
 * ```tsx
 * const { isListening, error } = useVoskWakeWord({
 *   wakeWord: '你好拉维斯',
 *   onWake: () => {
 *     console.log('Wake word detected!');
 *     startRecording();
 *   }
 * });
 * ```
 */
export function useVoskWakeWord({
  wakeWord = 'hi lavis',
  modelPath = '/models/vosk-model-small-en-us-0.15.tar.gz',
  onWake,
  enabled = true
}: UseVoskWakeWordProps): UseVoskWakeWordReturn {
  const [isListening, setIsListening] = useState(false);
  const [isModelLoaded, setIsModelLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Refs
  const voskRef = useRef<VoskModule | null>(null);
  const modelRef = useRef<VoskModel | null>(null);
  const recognizerRef = useRef<VoskRecognizer | null>(null);
  const audioContextRef = useRef<AudioContext | null>(null);
  const mediaStreamRef = useRef<MediaStream | null>(null);
  const processorRef = useRef<ScriptProcessorNode | null>(null);
  const onWakeRef = useRef(onWake);

  // 保持 onWake 回调的最新引用
  useEffect(() => {
    onWakeRef.current = onWake;
  }, [onWake]);

  /**
   * 加载 Vosk 模块和模型
   */
  const loadVosk = useCallback(async () => {
    try {
      // 动态导入 vosk-browser
      const voskModule = await import('vosk-browser');
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      voskRef.current = (voskModule.default || voskModule) as any;

      // 加载模型
      if (!voskRef.current) {
        throw new Error('Vosk module not loaded');
      }
      
      const model = await voskRef.current.createModel(modelPath);
      modelRef.current = model;

      // 设置日志级别（0=INFO, 1=WARN, 2=ERROR）
      try {
        model.setLogLevel(0);
      } catch (e) {
        // Ignore log level setting errors
      }

      setIsModelLoaded(true);
      return model;
    } catch (err) {
      console.error('[Vosk] Failed to load:', err);

      let errorMessage = 'Vosk 加载失败';
      if (err instanceof Error) {
        if (err.message.includes('404') || err.message.includes('not found')) {
          errorMessage = `模型文件未找到，请确保 ${modelPath} 目录存在`;
        } else if (err.message.includes('wasm')) {
          errorMessage = 'WASM 文件加载失败，请检查 public/lib/vosk/ 目录';
        } else {
          errorMessage = err.message;
        }
      }

      setError(errorMessage);
      throw err;
    }
  }, [modelPath]);

  /**
   * 初始化语音识别
   */
  const initRecognizer = useCallback(async () => {
    if (!modelRef.current) {
      console.error('[Vosk] Model not loaded');
      return;
    }

    try {
      // 获取麦克风权限
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          sampleRate: 16000,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        }
      });
      mediaStreamRef.current = stream;

      // 创建 AudioContext
      const AudioContextClass = window.AudioContext ||
        (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      const audioContext = new AudioContextClass({ sampleRate: 16000 });
      audioContextRef.current = audioContext;

      // 获取实际采样率（可能与请求的不同）
      const sampleRate = audioContext.sampleRate;

      // 创建识别器 - 不使用 Grammar 限制，让模型自由识别
      // 原因：Grammar 限制会导致模型只能识别 Grammar 中的词，如果模型不认识唤醒词中的某个词，
      // 就会返回 [unk]，导致识别失败。改为自由识别后再匹配唤醒词，准确度更高。
      const recognizer = new modelRef.current.KaldiRecognizer(sampleRate);
      recognizerRef.current = recognizer;

      // 监听识别结果
      recognizer.on('result', (message: VoskResultMessage) => {
        // vosk-browser 0.0.8 的 result 事件回调直接传递结果对象
        // 结构可能是: { text: "...", result: [...] } 或 { result: { text: "...", ... } }
        let text: string | undefined;
        
        // 尝试多种可能的结构
        if (typeof message === 'object') {
          // 情况1: message 直接包含 text (vosk-browser 常见格式)
          if ('text' in message && typeof (message as any).text === 'string') {
            text = (message as any).text.trim();
          }
          // 情况2: message.result.text
          else if ('result' in message && typeof (message as any).result === 'object') {
            const result = (message as any).result;
            if (result && typeof result.text === 'string') {
              text = result.text.trim();
            }
          }
          // 情况3: message 本身可能就是结果对象
          else if ((message as any).text) {
            text = String((message as any).text).trim();
          }
        }

        if (text) {
          // 检查是否匹配唤醒词
          // 支持大小写不敏感和空格变化（例如："hi lavis", "Hi Lavis", "hi lavis" 等）
          const normalizeText = (str: string) => 
            str.toLowerCase().replace(/\s+/g, ' ').trim();
          
          const normalizedText = normalizeText(text);
          const normalizedWakeWord = normalizeText(wakeWord);
          
          // 匹配策略（按优先级，从严格到宽松）：
          // 1. 完全匹配：识别结果完全等于唤醒词
          // 2. 包含匹配：识别结果包含完整的唤醒词（前后可能有其他词）
          // 3. 开头匹配：识别结果以唤醒词开头
          // 4. 音近词映射匹配：检查映射表中的完整短语匹配
          // 5. 词级音近匹配：使用改进的词级匹配算法
          // 6. Soundex 音素匹配：基于音素的相似度匹配
          const isExactMatch = normalizedText === normalizedWakeWord;
          const isPartialMatch = normalizedText.includes(normalizedWakeWord);
          const startsWithWakeWord = normalizedText.startsWith(normalizedWakeWord);
            
          // 音近词匹配：处理 Vosk 对不常见词的误识别（如 "lavis" -> "lay reese"）
          let isPhoneticMatch = false;
              
          if (!isExactMatch && !isPartialMatch && !startsWithWakeWord) {
            // 策略1: 检查整体音近匹配（映射表中的完整短语）
            // 例如 "hello is" 在映射表中对应 "hi lavis"
            const similarPhrases = PHONETIC_SIMILAR_WORDS[normalizedWakeWord] || [];
            
            // 检查识别结果是否完全匹配映射表中的某个短语
            if (similarPhrases.includes(normalizedText)) {
              isPhoneticMatch = true;
            }
            // 检查识别结果是否包含映射表中的短语，或映射表中的短语包含识别结果
            else if (similarPhrases.some(phrase => {
              return normalizedText.includes(phrase) || 
                     phrase.includes(normalizedText) ||
                     // 使用 Soundex 检查音素相似度
                     (soundex(normalizedText.replace(/\s+/g, '')) === soundex(phrase.replace(/\s+/g, '')));
            })) {
              isPhoneticMatch = true;
            }
            // 策略2: 词级匹配（改进的算法，支持 Soundex 和更宽松的匹配）
            else {
              isPhoneticMatch = wordLevelMatch(normalizedText, normalizedWakeWord);
            }
            
            // 策略3: 如果以上都失败，尝试 Soundex 整体匹配
            if (!isPhoneticMatch) {
              const textSoundex = soundex(normalizedText.replace(/\s+/g, ''));
              const wakeSoundex = soundex(normalizedWakeWord.replace(/\s+/g, ''));
              if (textSoundex && wakeSoundex && textSoundex === wakeSoundex) {
                isPhoneticMatch = true;
              }
            }
          }
          
          // 综合判断
          if (isExactMatch || isPartialMatch || startsWithWakeWord || isPhoneticMatch) {
            onWakeRef.current?.();
          } else {
            // 未匹配，不执行任何操作
          }
        }
      });

      // 监听部分识别结果（用于调试和实时反馈）
      recognizer.on('partialresult', () => {
        // 部分结果仅用于内部处理，不输出日志
      });
      
      // 监听错误
      recognizer.on('error', (message: VoskResultMessage) => {
        const errorMsg = (message as any).error || 'Unknown error';
        console.error('[Vosk] Recognizer error:', errorMsg);
        setError(`Vosk 识别错误: ${errorMsg}`);
      });

      // 创建音频处理节点
      const source = audioContext.createMediaStreamSource(stream);
      const processor = audioContext.createScriptProcessor(4096, 1, 1);
      processorRef.current = processor;

      // 处理音频数据 - vosk-browser 接受 AudioBuffer
      processor.onaudioprocess = (event) => {
        if (recognizerRef.current) {
          // 直接传递 inputBuffer (AudioBuffer)
          try {
            recognizerRef.current.acceptWaveform(event.inputBuffer);
          } catch (err) {
            console.error('[Vosk] Error in acceptWaveform:', err);
          }
        }
      };

      // 连接音频节点
      source.connect(processor);
      // 注意：连接到 destination 会导致音频输出，可能产生反馈
      // 如果不需要监听，可以连接到空的 GainNode 或断开连接
      const gainNode = audioContext.createGain();
      gainNode.gain.value = 0; // 静音，避免反馈
      processor.connect(gainNode);
      gainNode.connect(audioContext.destination);

      setIsListening(true);
      setError(null);

    } catch (err) {
      console.error('[Vosk] Failed to initialize recognizer:', err);

      let errorMessage = '语音识别初始化失败';
      if (err instanceof Error) {
        if (err.message.includes('Permission') || err.message.includes('NotAllowed')) {
          errorMessage = '麦克风权限被拒绝，请授予权限后重试';
        } else if (err.message.includes('NotFound')) {
          errorMessage = '未找到麦克风设备';
        } else {
          errorMessage = err.message;
        }
      }

      setError(errorMessage);
      setIsListening(false);
    }
  }, [wakeWord]);

  /**
   * 停止监听
   */
  const stopListening = useCallback(() => {
    // 断开音频处理
    if (processorRef.current) {
      processorRef.current.disconnect();
      processorRef.current = null;
    }

    // 关闭 AudioContext
    if (audioContextRef.current) {
      audioContextRef.current.close().catch(() => {});
      audioContextRef.current = null;
    }

    // 停止媒体流
    if (mediaStreamRef.current) {
      mediaStreamRef.current.getTracks().forEach(track => track.stop());
      mediaStreamRef.current = null;
    }

    // 释放识别器
    if (recognizerRef.current) {
      try {
        recognizerRef.current.remove();
      } catch (e) {
        // Ignore removal errors
      }
      recognizerRef.current = null;
    }

    setIsListening(false);
  }, []);

  /**
   * 开始监听
   */
  const startListening = useCallback(async () => {
    if (isListening) {
      return;
    }

    try {
      // 如果模型未加载，先加载
      if (!modelRef.current) {
        await loadVosk();
      }

      // 初始化识别器
      await initRecognizer();
    } catch (err) {
      console.error('[Vosk] Failed to start listening:', err);
      const errorMsg = err instanceof Error ? err.message : String(err);
      setError(`启动监听失败: ${errorMsg}`);
      setIsListening(false);
    }
  }, [isListening, loadVosk, initRecognizer]);

  // 根据 enabled 状态自动启动/停止
  useEffect(() => {
    if (enabled && !isListening && !error) {
      startListening().catch((err) => {
        console.error('[Vosk] Failed to start listening:', err);
        const errorMsg = err instanceof Error ? err.message : String(err);
        setError(`启动失败: ${errorMsg}`);
      });
    } else if (!enabled && isListening) {
      stopListening();
    }

    // Cleanup: 只在组件卸载时停止
    return () => {
      if (!enabled) {
        stopListening();
      }
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, error]);

  return {
    isListening,
    isModelLoaded,
    error,
    startListening,
    stopListening,
  };
}