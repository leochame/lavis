import { useState, useRef, useEffect } from 'react';
import type { CSSProperties, ForwardRefExoticComponent, ReactNode, RefAttributes } from 'react';
import ReactMarkdown from 'react-markdown';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { agentApi } from '../api/agentApi';
import { BrainPanel } from './BrainPanel';
import { VoicePanel } from './VoicePanel';
import { ManagementPanel } from './ManagementPanel';
import { SchedulerPanel } from './SchedulerPanel';
import { SkillsPanel } from './SkillsPanel';
import { Sidebar, type PanelType } from './Sidebar';
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

type FixedSizeListHandle = {
  scrollToItem: (index: number, align?: 'auto' | 'smart' | 'start' | 'center' | 'end') => void;
};

type FixedSizeListProps = {
  height: number;
  width: number | string;
  itemCount: number;
  itemSize: number;
  style?: CSSProperties;
  children: (props: { index: number; style: CSSProperties }) => ReactNode;
};

type FixedSizeListComponent = ForwardRefExoticComponent<
  FixedSizeListProps & RefAttributes<FixedSizeListHandle>
>;

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
  const [showVoicePanel, setShowVoicePanel] = useState(false);
  const [activePanel, setActivePanel] = useState<PanelType>('chat');
  const [FixedSizeList, setFixedSizeList] = useState<FixedSizeListComponent | null>(null);
  const listRef = useRef<FixedSizeListHandle | null>(null);
  const messagesContainerRef = useRef<HTMLDivElement>(null);
  
  // 【内存安全】获取窗口状态，在 Listening/Idle 模式下停止渲染复杂组件
  const windowState = useUIStore((s) => s.windowState);
  const shouldRenderComplexComponents = windowState === 'expanded';
  
  // 动态加载 react-window
  useEffect(() => {
    import('react-window').then((module) => {
      // react-window 是 CommonJS 模块，可能需要从 default 或命名导出中获取
      const typedModule = module as unknown as {
        FixedSizeList?: FixedSizeListComponent;
        default?: FixedSizeListComponent | { FixedSizeList?: FixedSizeListComponent };
      };
      const ListComponent =
        typedModule.FixedSizeList ||
        (typedModule.default && typeof typedModule.default === 'object'
          ? (typedModule.default as { FixedSizeList?: FixedSizeListComponent }).FixedSizeList
          : typedModule.default);
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
  const { connected, status: wsStatus, workflow, resetWorkflow, sendMessage } = useWebSocket(agentApi.getWebSocketUrl());

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
          content: `[Voice] ${globalVoice.transcribedText}`,
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

  // Auto-show Brain panel when workflow is active
  useEffect(() => {
    if (workflow.status === 'executing' || workflow.status === 'planning' || workflow.steps.length > 0) {
      setActivePanel('brain');
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

    // 确保 WebSocket 连接保持活跃，继续监听后端状态
    if (connected) {
      // 发送订阅消息，确保后端知道前端正在监听
      sendMessage('subscribe', {});
      // 发送 ping 保持连接活跃
      sendMessage('ping', {});
    }

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
  const isPlanning = workflow.status === 'planning';
  const isWorking = isExecuting || isPlanning || isLoading;
  
  // 判断是否应该显示工作状态指示器：WebSocket 连接正常且后端正在工作
  const shouldShowWorkingIndicator = connected && isWorking;
  
  // Debug: log working indicator state
  useEffect(() => {
    if (connected || isWorking) {
      console.log('ChatPanel working indicator:', {
        connected: String(connected),
        isWorking: String(isWorking),
        workflowStatus: String(workflow.status),
        isExecuting: String(isExecuting),
        isPlanning: String(isPlanning),
        isLoading: String(isLoading),
        shouldShowWorkingIndicator: String(shouldShowWorkingIndicator)
      });
    }
  }, [connected, isWorking, workflow.status, isExecuting, isPlanning, isLoading, shouldShowWorkingIndicator]);

  // Render the active panel content
  const renderPanelContent = () => {
    switch (activePanel) {
      case 'brain':
        return (
          <BrainPanel
            workflow={workflow}
            connectionStatus={wsStatus}
            onStop={handleEmergencyStop}
          />
        );
      case 'management':
        return <ManagementPanel onClose={() => setActivePanel('chat')} />;
      case 'scheduler':
        return <SchedulerPanel />;
      case 'skills':
        return <SkillsPanel />;
      case 'chat':
      default:
        return (
          <>
            <div
              ref={messagesContainerRef}
              className="chat-panel__messages"
              style={{ position: 'relative' }}
            >
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
                    {({ index, style }: { index: number; style: CSSProperties }) => {
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
                    <p>Start conversation...</p>
                  </div>
                )
              ) : (
                <div className="chat-panel__messages-placeholder">
                  <p>Window is in {windowState} mode</p>
                  <p>Double-click capsule to expand</p>
                </div>
              )}
            </div>

            <div className="chat-panel__input-container">
              {showVoicePanel && (
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
              )}
              <form className="chat-panel__input" onSubmit={handleSubmit}>
                <input
                  type="text"
                  value={input}
                  onChange={(e) => setInput(e.target.value)}
                  placeholder={wsStatus === 'connected' ? "Type a message..." : "Connecting..."}
                  disabled={isLoading || isExecuting || wsStatus !== 'connected'}
                  autoFocus
                />
                <button type="submit" disabled={!input.trim() || isLoading || isExecuting || wsStatus !== 'connected'}>
                  Send
                </button>
              </form>
            </div>
          </>
        );
    }
  };

  return (
    <div className="chat-panel">
      {/* Sidebar Navigation */}
      <Sidebar
        activePanel={activePanel}
        onPanelChange={setActivePanel}
        showVoice={showVoicePanel}
        onToggleVoice={() => setShowVoicePanel(!showVoicePanel)}
        showScreenshot={showScreenshot}
        onToggleScreenshot={handleScreenshotClick}
        onClose={onClose}
        isConnected={connected}
        isWorking={isWorking}
      />

      {/* Main Content */}
      <div className="chat-panel__content">
        {/* Screenshot Preview */}
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

        {/* Panel Content */}
        <div className="chat-panel__body">
          <div className="chat-panel__main">
            {shouldRenderComplexComponents ? renderPanelContent() : (
              <div className="chat-panel__messages-placeholder">
                <p>Window minimized</p>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

