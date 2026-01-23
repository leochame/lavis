import { useState, useRef, useEffect } from 'react';
import ReactMarkdown from 'react-markdown';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
// 使用动态导入以兼容 CommonJS 模块
import type { FixedSizeList as FixedSizeListType } from 'react-window';
import { agentApi } from '../api/agentApi';
import { WorkflowPanel } from './WorkflowPanel';
import { VoicePanel } from './VoicePanel';
import { useWebSocket } from '../hooks/useWebSocket';
import { useUIStore } from '../store/uiStore';
import type { AgentStatus } from '../types/agent';
import type { UseGlobalVoiceReturn } from '../hooks/useGlobalVoice';
import './ChatPanel.css';

interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: number;
}

interface ChatPanelProps {
  onClose: () => void;
  status: AgentStatus | null;
  /** 全局语音控制 (来自 App.tsx) */
  globalVoice: UseGlobalVoiceReturn;
}

export function ChatPanel({ onClose, status, globalVoice }: ChatPanelProps) {
  const [input, setInput] = useState('');
  const [messages, setMessages] = useState<Message[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [showScreenshot, setShowScreenshot] = useState(false);
  const [screenshotData, setScreenshotData] = useState<string | null>(null);
  const [isCapturing, setIsCapturing] = useState(false);
  const [showVoicePanel, setShowVoicePanel] = useState(false);
  const [showBrain, setShowBrain] = useState(true); // 默认显示思维透视
  const [FixedSizeList, setFixedSizeList] = useState<typeof FixedSizeListType | null>(null);
  const listRef = useRef<FixedSizeListType | null>(null);
  const messagesContainerRef = useRef<HTMLDivElement>(null);
  
  // 【内存安全】获取窗口状态，在 Listening/Idle 模式下停止渲染复杂组件
  const windowState = useUIStore((s) => s.windowState);
  const shouldRenderComplexComponents = windowState === 'expanded';
  
  // 动态加载 react-window
  useEffect(() => {
    import('react-window').then((module) => {
      // react-window 是 CommonJS 模块，可能需要从 default 或命名导出中获取
      const ListComponent = (module as any).FixedSizeList || (module as any).default?.FixedSizeList || module.default;
      if (ListComponent) {
        setFixedSizeList(() => ListComponent);
      }
    }).catch((err) => {
      console.error('Failed to load react-window:', err);
    });
  }, []);
  
  // 计算消息列表容器高度（动态计算，减去 header 和 input 的高度）
  const [containerHeight, setContainerHeight] = useState(600);
  
  useEffect(() => {
    const updateHeight = () => {
      if (messagesContainerRef.current) {
        const rect = messagesContainerRef.current.getBoundingClientRect();
        setContainerHeight(rect.height);
      }
    };
    
    updateHeight();
    window.addEventListener('resize', updateHeight);
    return () => window.removeEventListener('resize', updateHeight);
  }, []);

  // WebSocket connection for real-time workflow updates
  const { connected, status: wsStatus, workflow, resetWorkflow } = useWebSocket(agentApi.getWebSocketUrl());

  // 自动滚动到底部（新消息到达时）
  useEffect(() => {
    if (listRef.current && messages.length > 0) {
      // 使用 setTimeout 确保 DOM 更新后再滚动
      setTimeout(() => {
        listRef.current?.scrollToItem(messages.length - 1, 'end');
      }, 0);
    }
  }, [messages, isLoading]);

  // 当语音对话完成时，将消息添加到聊天记录
  useEffect(() => {
    if (globalVoice.transcribedText && globalVoice.agentResponse && globalVoice.voiceState === 'idle') {
      const lastMessage = messages[messages.length - 1];
      if (lastMessage?.content !== globalVoice.agentResponse) {
        const userMessage: Message = {
          id: Date.now().toString(),
          role: 'user',
          content: `🎤 ${globalVoice.transcribedText}`,
          timestamp: Date.now(),
        };
        const assistantMessage: Message = {
          id: (Date.now() + 1).toString(),
          role: 'assistant',
          content: globalVoice.agentResponse,
          timestamp: Date.now(),
        };
        setMessages(prev => [...prev, userMessage, assistantMessage]);
      }
    }
  }, [globalVoice.transcribedText, globalVoice.agentResponse, globalVoice.voiceState, messages]);

  // 当有工作流活动时自动显示 Brain 面板
  useEffect(() => {
    if (workflow.status === 'executing' || workflow.steps.length > 0) {
      setShowBrain(true);
    }
  }, [workflow.status, workflow.steps.length]);

  // 估算每条消息平均高度（包含 padding 和 gap）
  const estimatedItemHeight = 150;

  const handleScreenshotClick = async () => {
    if (showScreenshot) {
      setShowScreenshot(false);
      setScreenshotData(null);
    } else {
      try {
        setIsCapturing(true);

        const mediaStream = await navigator.mediaDevices.getDisplayMedia({
          video: {
            displaySurface: 'browser',
            frameRate: 30,
          } as MediaTrackConstraints,
          audio: false,
        });

        const videoTrack = mediaStream.getVideoTracks()[0];
        const imageCapture = new ImageCapture(videoTrack);
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const bitmap = await (imageCapture as any).grabFrame() as ImageBitmap;

        const canvas = document.createElement('canvas');
        canvas.width = bitmap.width;
        canvas.height = bitmap.height;
        const ctx = canvas.getContext('2d');
        ctx?.drawImage(bitmap, 0, 0);

        const dataUrl = canvas.toDataURL('image/png');
        const base64Data = dataUrl.split(',')[1];

        mediaStream.getTracks().forEach(track => track.stop());

        setScreenshotData(base64Data);
        setShowScreenshot(true);
      } catch (error) {
        if (error instanceof Error && error.name === 'NotAllowedError') {
          console.log('User cancelled screen capture');
        } else {
          console.error('Failed to capture screen:', error);
        }
      } finally {
        setIsCapturing(false);
      }
    }
  };

  const handleEmergencyStop = async () => {
    try {
      await agentApi.stop();
    } catch (error) {
      console.error('Failed to stop:', error);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!input.trim() || isLoading) return;

    const userMessage: Message = {
      id: Date.now().toString(),
      role: 'user',
      content: input,
      timestamp: Date.now(),
    };

    setMessages((prev) => [...prev, userMessage]);
    setInput('');
    setIsLoading(true);
    resetWorkflow(); // Reset workflow state for new task

    try {
      const response = await agentApi.chat({ message: input });
      const assistantMessage: Message = {
        id: (Date.now() + 1).toString(),
        role: 'assistant',
        content: response.response,
        timestamp: Date.now(),
      };
      setMessages((prev) => [...prev, assistantMessage]);
    } catch (error) {
      const errorMessage: Message = {
        id: (Date.now() + 1).toString(),
        role: 'assistant',
        content: `Error: ${(error as Error).message}`,
        timestamp: Date.now(),
      };
      setMessages((prev) => [...prev, errorMessage]);
    } finally {
      setIsLoading(false);
    }
  };

  const isExecuting = workflow.status === 'executing' || status?.orchestrator_state?.includes('EXECUTING');
  const showWorkflow = workflow.steps.length > 0 || workflow.status !== 'idle';

  // 根据 WebSocket 状态获取标题颜色
  const getWsStatusColor = () => {
    switch (wsStatus) {
      case 'connected': return '#00ff9d';
      case 'connecting': return '#ffa500';
      default: return '#ff3333';
    }
  };

  const getWsStatusTitle = () => {
    switch (wsStatus) {
      case 'connected': return 'WebSocket Connected';
      case 'connecting': return 'Reconnecting...';
      default: return 'WebSocket Disconnected';
    }
  };

  return (
    <div className="chat-panel">
      {/* Header */}
      <div className="chat-panel__header">
        <div className="chat-panel__header-left">
          <h2>Lavis AI</h2>
          <div
            className={`chat-panel__ws-status ${connected ? 'chat-panel__ws-status--connected' : ''}`}
            style={{
              backgroundColor: getWsStatusColor(),
              boxShadow: `0 0 6px ${getWsStatusColor()}`,
              animation: wsStatus === 'connecting' ? 'pulse-ws 1s infinite' : undefined
            }}
            title={getWsStatusTitle()}
          />
          <button
            className={`chat-panel__screenshot ${showScreenshot ? 'chat-panel__screenshot--active' : ''}`}
            onClick={handleScreenshotClick}
            title="Capture screen"
            disabled={isCapturing}
          >
            {isCapturing ? '⏳' : '📷'}
          </button>
          <button
            className={`chat-panel__voice-toggle ${showVoicePanel ? 'chat-panel__voice-toggle--active' : ''}`}
            onClick={() => setShowVoicePanel(!showVoicePanel)}
            title={showVoicePanel ? '切换到文字输入' : '切换到语音输入'}
          >
            🎤
          </button>
          <button
            className={`chat-panel__brain-btn ${showBrain ? 'chat-panel__brain-btn--active' : ''}`}
            onClick={() => setShowBrain(!showBrain)}
            title={showBrain ? '隐藏思维透视' : '显示思维透视'}
          >
            🧠
          </button>
        </div>
        <button className="chat-panel__close" onClick={onClose}>×</button>
      </div>

      {showScreenshot && screenshotData && (
        <div className="chat-panel__screenshot-preview">
          <button
            className="chat-panel__screenshot-close"
            onClick={() => setShowScreenshot(false)}
          >
            ×
          </button>
          <img src={`data:image/png;base64,${screenshotData}`} alt="Screenshot" />
        </div>
      )}

      {/* Body - 左右分栏 */}
      <div className="chat-panel__body">
        {/* 左侧：聊天区 */}
        <div className="chat-panel__main">
          <div 
            ref={messagesContainerRef}
            className="chat-panel__messages"
            style={{ position: 'relative' }}
          >
            {/* 【内存安全】仅在 Expanded 模式下渲染消息列表，减少 DOM 节点 */}
            {shouldRenderComplexComponents ? (
              messages.length > 0 && FixedSizeList ? (
                <FixedSizeList
                  ref={listRef}
                  height={containerHeight}
                  itemCount={messages.length + (isLoading ? 1 : 0)}
                  itemSize={estimatedItemHeight}
                  width="100%"
                  style={{ padding: '20px' }}
                >
                  {({ index, style }) => {
                    // 如果是加载中的消息
                    if (index === messages.length) {
                      return (
                        <div style={style}>
                          <div className="message message--assistant">
                            <div className="message__content message__loading">
                              <span>.</span><span>.</span><span>.</span>
                            </div>
                          </div>
                        </div>
                      );
                    }
                    
                    const message = messages[index];
                    return (
                      <div style={{ ...style, paddingBottom: '16px' }}>
              <div
                key={message.id}
                className={`message message--${message.role}`}
              >
                <div className="message__content">
                  {message.role === 'assistant' ? (
                    <ReactMarkdown
                      components={{
                        // eslint-disable-next-line @typescript-eslint/no-explicit-any
                        code({ className, children, ...props }: any) {
                          const match = /language-(\w+)/.exec(className || '');
                          const isInline = !match;
                          return !isInline && match ? (
                            <SyntaxHighlighter
                              style={oneDark}
                              language={match[1]}
                              PreTag="div"
                              {...props}
                            >
                              {String(children).replace(/\n$/, '')}
                            </SyntaxHighlighter>
                          ) : (
                            <code className={className} {...props}>
                              {children}
                            </code>
                          );
                        },
                      }}
                    >
                      {message.content}
                    </ReactMarkdown>
                  ) : (
                    message.content
                  )}
                </div>
                <div className="message__timestamp">
                  {new Date(message.timestamp).toLocaleTimeString()}
                </div>
              </div>
                      </div>
                    );
                  }}
                </FixedSizeList>
              ) : isLoading ? (
              <div className="message message--assistant">
                <div className="message__content message__loading">
                  <span>.</span><span>.</span><span>.</span>
                </div>
                </div>
              ) : (
                <div className="chat-panel__messages-empty">
                  <p>开始对话...</p>
                </div>
              )
            ) : (
              <div className="chat-panel__messages-placeholder">
                <p>窗口处于 {windowState} 模式，消息列表已暂停渲染以节省内存</p>
                <p>双击胶囊展开窗口以查看完整聊天记录</p>
              </div>
            )}
          </div>

          {/* 输入区 */}
          {showVoicePanel ? (
            <div className="chat-panel__voice-container">
              <VoicePanel
                status={status}
                voiceState={globalVoice.voiceState}
                isRecording={globalVoice.isRecording}
                isWakeWordListening={globalVoice.isWakeWordListening}
                transcribedText={globalVoice.transcribedText}
                agentResponse={globalVoice.agentResponse}
                error={globalVoice.error}
                onStartRecording={globalVoice.startRecording}
                onStopRecording={globalVoice.stopRecording}
              />
            </div>
          ) : (
            <form className="chat-panel__input" onSubmit={handleSubmit}>
              <input
                type="text"
                value={input}
                onChange={(e) => setInput(e.target.value)}
                placeholder={wsStatus === 'connected' ? "Type a message..." : "Connecting to brain..."}
                disabled={isLoading || isExecuting || wsStatus !== 'connected'}
                autoFocus
              />
              <button type="submit" disabled={!input.trim() || isLoading || isExecuting || wsStatus !== 'connected'}>
                Send
              </button>
            </form>
          )}
        </div>

        {/* 右侧：思维透视 (Brain) */}
        {/* 【内存安全】仅在 Expanded 模式下渲染 WorkflowPanel，减少内存占用 */}
        {shouldRenderComplexComponents && (
        <div className={`chat-panel__brain ${!showBrain ? 'chat-panel__brain--collapsed' : ''}`}>
          {showBrain && (
            <>
              <div className="chat-panel__brain-header">
                <div className="chat-panel__brain-title">
                  <div className="chat-panel__brain-icon" />
                  <span>BRAIN</span>
                </div>
              </div>
              <div className="chat-panel__brain-content">
                {showWorkflow ? (
                  <WorkflowPanel
                    workflow={workflow}
                    connected={connected}
                    onStop={handleEmergencyStop}
                  />
                ) : (
                  <div className="chat-panel__brain-empty">
                    <div className="chat-panel__brain-empty-icon">🧠</div>
                    <div className="chat-panel__brain-empty-text">
                      思维透视区域
                      <br />
                      当 Agent 开始工作时，这里将实时展示思考过程
                    </div>
                  </div>
                )}
              </div>
            </>
          )}
        </div>
        )}
      </div>
    </div>
  );
}
