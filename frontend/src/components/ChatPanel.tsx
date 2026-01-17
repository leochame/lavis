import { useState, useRef, useEffect } from 'react';
import ReactMarkdown from 'react-markdown';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { agentApi } from '../api/agentApi';
import { TaskPanel } from './TaskPanel';
import type { AgentStatus } from '../types/agent';
import './ChatPanel.css';

interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: number;
}

export function ChatPanel({ onClose, status }: { onClose: () => void; status: AgentStatus | null }) {
  const [input, setInput] = useState('');
  const [messages, setMessages] = useState<Message[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [showScreenshot, setShowScreenshot] = useState(false);
  const [screenshotData, setScreenshotData] = useState<string | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  const handleScreenshotClick = async () => {
    if (showScreenshot) {
      setShowScreenshot(false);
    } else {
      try {
        const screenshot = await agentApi.getScreenshot();
        setScreenshotData(screenshot.image);
        setShowScreenshot(true);
      } catch (error) {
        console.error('Failed to get screenshot:', error);
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

  const isExecuting = status?.orchestrator_state?.includes('EXECUTING');

  return (
    <div className="chat-panel">
      <div className="chat-panel__header">
        <div className="chat-panel__header-left">
          <h2>Lavis AI</h2>
          <button
            className={`chat-panel__screenshot ${showScreenshot ? 'chat-panel__screenshot--active' : ''}`}
            onClick={handleScreenshotClick}
            title="View what's agent sees"
          >
            📷
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
                    code({ node, inline, className, children, ...props }: any) {
                      const match = /language-(\w+)/.exec(className || '');
                      return !inline && match ? (
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

      {isExecuting && (
        <TaskPanel status={status} onEmergencyStop={handleEmergencyStop} />
      )}

      <form className="chat-panel__input" onSubmit={handleSubmit}>
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="Type a message..."
          disabled={isLoading || isExecuting}
          autoFocus
        />
        <button type="submit" disabled={!input.trim() || isLoading || isExecuting}>
          Send
        </button>
      </form>
    </div>
  );
}
