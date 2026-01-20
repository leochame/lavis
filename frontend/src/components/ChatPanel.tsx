import { useState, useRef, useEffect } from 'react';
import ReactMarkdown from 'react-markdown';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { agentApi } from '../api/agentApi';
import { WorkflowPanel } from './WorkflowPanel';
import { VoicePanel } from './VoicePanel';
import { useWebSocket } from '../hooks/useWebSocket';
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
  const messagesEndRef = useRef<HTMLDivElement>(null);

  // WebSocket connection for real-time workflow updates
  // 使用新的 status 状态来提供更好的 UI 反馈
  const { connected, status: wsStatus, workflow, resetWorkflow } = useWebSocket();

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

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

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  const handleScreenshotClick = async () => {
    if (showScreenshot) {
      setShowScreenshot(false);
      setScreenshotData(null);
    } else {
      try {
        setIsCapturing(true);

        // @ts-expect-error - TypeScript may not have complete type definitions for getDisplayMedia
        const mediaStream = await navigator.mediaDevices.getDisplayMedia({
          video: {
            displaySurface: 'browser',
            frameRate: 30,
            cursor: 'never',
          },
          audio: false,
        });

        const videoTrack = mediaStream.getVideoTracks()[0];
        const imageCapture = new ImageCapture(videoTrack);
        const bitmap = await imageCapture.grabFrame();

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
      case 'connecting': return '#ffa500'; // 橙色表示连接中
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
      <div className="chat-panel__header">
        <div className="chat-panel__header-left">
          <h2>Lavis AI</h2>
          {/* Enhanced WebSocket Status Indicator */}
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

      <div className="chat-panel__messages">
        {messages.map((message) => (
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
        ))}
        {isLoading && (
          <div className="message message--assistant">
            <div className="message__content message__loading">
              <span>.</span><span>.</span><span>.</span>
            </div>
          </div>
        )}
        <div ref={messagesEndRef} />
      </div>

      {showWorkflow && (
        <WorkflowPanel 
          workflow={workflow} 
          connected={connected}
          onStop={handleEmergencyStop} 
        />
      )}

      {showVoicePanel ? (
        <div className="chat-panel__voice-container">
          <VoicePanel 
            status={status}
            voiceState={globalVoice.voiceState}
            isRecording={globalVoice.isRecording}
            isWakeWordListening={globalVoice.isWakeWordListening}
            transcribedText={globalVoice.transcribedText}
            agentResponse={globalVoice.agentResponse}
            agentAudio={globalVoice.agentAudio}
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
  );
}