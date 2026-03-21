import { startTransition, useCallback, useDeferredValue, useEffect, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import { agentApi } from '../api/agentApi';
import { BrainPanel } from './BrainPanel';
import { ManagementPanel } from './ManagementPanel';
import { SkillsPanel } from './SkillsPanel';
import { SchedulerPanel } from './SchedulerPanel';
import { SettingsPanel } from './SettingsPanel';
import { Sidebar, type PanelType } from './Sidebar';
import type { WorkflowState, ConnectionStatus } from '../hooks/useWebSocket';
import { useUIStore } from '../store/uiStore';
import { useChatStore, type ChatMessage } from '../store/chatStore';
import type { AgentStatus } from '../types/agent';
import type { UseGlobalVoiceReturn } from '../hooks/useGlobalVoice';
import './ChatPanel.css';

interface ChatPanelProps {
  onClose: () => void;
  status: AgentStatus | null;
  globalVoice: UseGlobalVoiceReturn;
  wsConnected: boolean;
  wsStatus: ConnectionStatus;
  workflow: WorkflowState;
  resetWorkflow: () => void;
  sendMessage: (type: string, data?: Record<string, unknown>) => void;
  mode?: 'full' | 'chat-only';
  onBindInput?: (bridge: {
    value: string;
    onChange: (value: string) => void;
    onSubmit: () => void;
    disabled: boolean;
    placeholder: string;
  }) => void;
  hideInput?: boolean;
}

const MESSAGE_CACHE_KEY = 'lavis_chat_messages_v3';
const MAX_DROP_FILE_COUNT = 6;
const MAX_DROP_TEXT_SIZE = 240_000;
const MAX_DROP_PREVIEW_LENGTH = 12_000;
const TEXT_LIKE_EXTENSIONS = new Set([
  'txt', 'md', 'markdown', 'json', 'yaml', 'yml', 'toml', 'xml',
  'html', 'css', 'js', 'jsx', 'ts', 'tsx', 'py', 'java', 'sql', 'sh',
]);

function createMessageId(prefix: ChatMessage['role'] | 'error'): string {
  const random = Math.random().toString(36).slice(2, 10);
  return `${prefix}-${Date.now()}-${random}`;
}

function loadCachedMessages(): ChatMessage[] {
  if (typeof window === 'undefined') return [];

  try {
    const raw = window.sessionStorage.getItem(MESSAGE_CACHE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw) as ChatMessage[];
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((message) => message && typeof message.content === 'string');
  } catch {
    return [];
  }
}

function formatBytes(value: number): string {
  if (value < 1024) return `${value}B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)}KB`;
  return `${(value / (1024 * 1024)).toFixed(1)}MB`;
}

function isTextLikeFile(file: File): boolean {
  if (file.type.startsWith('text/')) return true;
  const extension = file.name.split('.').pop()?.toLowerCase();
  return extension ? TEXT_LIKE_EXTENSIONS.has(extension) : false;
}

function truncateText(value: string, maxLength: number): string {
  if (value.length <= maxLength) return value;
  return `${value.slice(0, maxLength)}\n\n...(truncated)`;
}

export function ChatPanel({
  onClose,
  status,
  globalVoice,
  wsConnected: connected,
  wsStatus,
  workflow,
  resetWorkflow,
  sendMessage,
  mode = 'full',
  onBindInput,
  hideInput = false,
}: ChatPanelProps) {
  const input = useChatStore((s) => s.input);
  const setInput = useChatStore((s) => s.setInput);
  const messages = useChatStore((s) => s.messages);
  const setMessages = useChatStore((s) => s.setMessages);
  const appendMessage = useChatStore((s) => s.appendMessage);
  const appendMessagesBatch = useChatStore((s) => s.appendMessages);
  const clearChatStore = useChatStore((s) => s.clearMessages);
  const isLoading = useChatStore((s) => s.isLoading);
  const setLoading = useChatStore((s) => s.setLoading);
  const isDropActive = useChatStore((s) => s.isDropActive);
  const setDropActive = useChatStore((s) => s.setDropActive);
  const dropHint = useChatStore((s) => s.dropHint);
  const setDropHint = useChatStore((s) => s.setDropHint);
  const lastVoiceSignature = useChatStore((s) => s.lastVoiceSignature);
  const setLastVoiceSignature = useChatStore((s) => s.setLastVoiceSignature);
  const deferredMessages = useDeferredValue(messages);

  const [activePanel, setActivePanel] = useState<PanelType>('chat');
  const messagesContainerRef = useRef<HTMLDivElement>(null);
  const shouldStickToBottomRef = useRef(true);
  const dragDepthRef = useRef(0);
  const dropHintTimerRef = useRef<number | null>(null);
  const hasHydratedRef = useRef(false);

  const windowState = useUIStore((s) => s.windowState);
  const isEmbeddedMode = mode === 'chat-only';
  const shouldRenderComplexComponents = isEmbeddedMode || windowState === 'expanded';
  const isExecuting = workflow.status === 'executing' || Boolean(status?.orchestrator_state?.includes('EXECUTING'));
  const isPlanning = workflow.status === 'planning' || Boolean(status?.orchestrator_state?.includes('PLANNING'));
  const isWorking = isExecuting || isPlanning || isLoading;

  useEffect(() => {
    if (hasHydratedRef.current) return;
    hasHydratedRef.current = true;
    if (messages.length > 0) return;

    const cached = loadCachedMessages();
    if (cached.length > 0) {
      setMessages(cached);
    }
  }, [messages.length, setMessages]);

  const setTransientHint = useCallback((message: string) => {
    setDropHint(message);
    if (typeof window === 'undefined') return;
    if (dropHintTimerRef.current !== null) {
      window.clearTimeout(dropHintTimerRef.current);
    }
    dropHintTimerRef.current = window.setTimeout(() => {
      setDropHint(null);
      dropHintTimerRef.current = null;
    }, 2600);
  }, [setDropHint]);

  const appendMessages = useCallback((next: ChatMessage | ChatMessage[]) => {
    const incoming = Array.isArray(next) ? next : [next];
    startTransition(() => {
      appendMessagesBatch(incoming);
    });
  }, [appendMessagesBatch]);

  const scrollToBottom = useCallback((behavior: ScrollBehavior = 'auto') => {
    const container = messagesContainerRef.current;
    if (!container) return;
    container.scrollTo({ top: container.scrollHeight, behavior });
  }, []);

  const clearMessages = useCallback(() => {
    clearChatStore();
    if (typeof window !== 'undefined') {
      window.sessionStorage.removeItem(MESSAGE_CACHE_KEY);
    }
  }, [clearChatStore]);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    window.sessionStorage.setItem(MESSAGE_CACHE_KEY, JSON.stringify(messages));
  }, [messages]);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    if (!shouldStickToBottomRef.current) return;

    const rafId = window.requestAnimationFrame(() => {
      scrollToBottom(isLoading ? 'auto' : 'smooth');
    });
    return () => window.cancelAnimationFrame(rafId);
  }, [deferredMessages.length, isLoading, scrollToBottom]);

  useEffect(() => {
    return () => {
      if (dropHintTimerRef.current !== null && typeof window !== 'undefined') {
        window.clearTimeout(dropHintTimerRef.current);
      }
    };
  }, []);

  useEffect(() => {
    if (globalVoice.voiceState !== 'idle') return;
    const transcribedText = globalVoice.transcribedText?.trim();
    const agentResponse = globalVoice.agentResponse?.trim();
    if (!transcribedText || !agentResponse) return;

    const signature = `${transcribedText}::${agentResponse}`;
    if (lastVoiceSignature === signature) return;

    setLastVoiceSignature(signature);
    const timestamp = Date.now();
    appendMessages([
      {
        id: createMessageId('user'),
        role: 'user',
        content: `[Voice] ${transcribedText}`,
        timestamp,
        source: 'voice',
      },
      {
        id: createMessageId('assistant'),
        role: 'assistant',
        content: agentResponse,
        timestamp: timestamp + 1,
        source: 'voice',
      },
    ]);
  }, [
    appendMessages,
    globalVoice.agentResponse,
    globalVoice.transcribedText,
    globalVoice.voiceState,
    lastVoiceSignature,
    setLastVoiceSignature,
  ]);

  useEffect(() => {
    if (mode !== 'full') return;
    const handleOpenSettings = () => setActivePanel('settings');
    window.addEventListener('lavis-open-settings', handleOpenSettings);
    return () => window.removeEventListener('lavis-open-settings', handleOpenSettings);
  }, [mode]);

  useEffect(() => {
    if (mode !== 'full') return;
    if (activePanel !== 'chat') return;
    if (
      (workflow.status === 'executing' || workflow.status === 'planning') &&
      workflow.steps.length > 0
    ) {
      const timer = window.setTimeout(() => setActivePanel('brain'), 900);
      return () => window.clearTimeout(timer);
    }
  }, [activePanel, mode, workflow.status, workflow.steps.length]);

  const submitInput = useCallback(async () => {
    const messageText = input.trim();
    if (!messageText || isLoading) return;

    const timestamp = Date.now();
    appendMessage({
      id: createMessageId('user'),
      role: 'user',
      content: messageText,
      timestamp,
      source: 'text',
    });

    setInput('');
    setLoading(true);
    resetWorkflow();

    if (connected) {
      sendMessage('subscribe', {});
      sendMessage('ping', {});
    }

    try {
      const response = await agentApi.chat({ message: messageText });
      appendMessage({
        id: createMessageId('assistant'),
        role: 'assistant',
        content: response.agent_text || response.response,
        timestamp: timestamp + 1,
        source: 'text',
      });
    } catch (error) {
      appendMessage({
        id: createMessageId('error'),
        role: 'assistant',
        content: `Error: ${(error as Error).message}`,
        timestamp: Date.now(),
        source: 'error',
      });
    } finally {
      setLoading(false);
    }
  }, [appendMessage, connected, input, isLoading, resetWorkflow, sendMessage, setInput, setLoading]);

  useEffect(() => {
    if (!onBindInput) return;
    onBindInput({
      value: input,
      onChange: (value: string) => setInput(value),
      onSubmit: () => {
        void submitInput();
      },
      disabled: isLoading || isExecuting,
      placeholder: wsStatus === 'connected'
        ? 'Type a message or drag files here...'
        : 'Backend reconnecting...',
    });
  }, [onBindInput, input, isLoading, isExecuting, setInput, submitInput, wsStatus]);

  const handleEmergencyStop = async () => {
    try {
      await agentApi.stop();
    } catch (error) {
      console.error('Failed to stop:', error);
    }
  };

  const applyDropPayload = useCallback(async (dataTransfer: DataTransfer) => {
    const sections: string[] = [];
    const files = Array.from(dataTransfer.files).slice(0, MAX_DROP_FILE_COUNT);

    for (const file of files) {
      if (isTextLikeFile(file) && file.size <= MAX_DROP_TEXT_SIZE) {
        try {
          const rawText = await file.text();
          const preview = truncateText(rawText.trim(), MAX_DROP_PREVIEW_LENGTH);
          sections.push([
            `File: ${file.name} (${formatBytes(file.size)})`,
            '```',
            preview || '(empty file)',
            '```',
          ].join('\n'));
        } catch {
          sections.push(`File: ${file.name} (${formatBytes(file.size)})\nUnable to read file content.`);
        }
      } else {
        sections.push(
          `File: ${file.name} (${formatBytes(file.size)})\nType: ${file.type || 'unknown'} (metadata only)`,
        );
      }
    }

    const droppedText = dataTransfer.getData('text/plain').trim();
    if (droppedText) {
      sections.push(`Dropped Text:\n${truncateText(droppedText, MAX_DROP_PREVIEW_LENGTH)}`);
    }

    if (sections.length === 0) {
      setTransientHint('Drop payload is empty.');
      return;
    }

    const payload = `Please use this dropped context:\n\n${sections.join('\n\n')}`;
    const currentInput = useChatStore.getState().input;
    setInput(currentInput ? `${currentInput}\n\n${payload}` : payload);

    if (files.length > 0 && droppedText) {
      setTransientHint(`Attached ${files.length} file(s) and text snippet.`);
      return;
    }
    if (files.length > 0) {
      setTransientHint(`Attached ${files.length} file(s) into input.`);
      return;
    }
    setTransientHint('Attached dropped text into input.');
  }, [setInput, setTransientHint]);

  const handleMessagesScroll = () => {
    const container = messagesContainerRef.current;
    if (!container) return;
    const distanceToBottom = container.scrollHeight - container.scrollTop - container.clientHeight;
    shouldStickToBottomRef.current = distanceToBottom < 64;
  };

  const handleDragEnter = (event: React.DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.stopPropagation();
    dragDepthRef.current += 1;
    setDropActive(true);
  };

  const handleDragOver = (event: React.DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.stopPropagation();
    if (!isDropActive) setDropActive(true);
  };

  const handleDragLeave = (event: React.DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.stopPropagation();
    dragDepthRef.current = Math.max(0, dragDepthRef.current - 1);
    if (dragDepthRef.current === 0) {
      setDropActive(false);
    }
  };

  const handleDrop = (event: React.DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.stopPropagation();
    dragDepthRef.current = 0;
    setDropActive(false);
    void applyDropPayload(event.dataTransfer);
  };

  const renderChatContent = () => (
    <>
      <div className="chat-panel__toolbar">
        <div className="chat-panel__toolbar-left">
          <span className="chat-panel__title">Conversation</span>
          <span className={`chat-panel__ws-badge chat-panel__ws-badge--${connected ? (isWorking ? 'working' : 'ready') : 'offline'}`}>
            {connected ? (isWorking ? 'Working' : 'Ready') : 'Offline'}
          </span>
        </div>
        <div className="chat-panel__toolbar-right">
          <button
            type="button"
            className="chat-panel__toolbar-btn"
            onClick={clearMessages}
            disabled={deferredMessages.length === 0}
          >
            Clear
          </button>
          {mode === 'full' && (
            <button
              type="button"
              className="chat-panel__toolbar-btn chat-panel__toolbar-btn--danger"
              onClick={onClose}
            >
              Close
            </button>
          )}
        </div>
      </div>

      <div
        className={`chat-panel__messages-shell ${isDropActive ? 'chat-panel__messages-shell--drop' : ''}`}
        onDragEnter={handleDragEnter}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
      >
        {dropHint && <div className="chat-panel__drop-hint">{dropHint}</div>}
        {isDropActive && (
          <div className="chat-panel__drop-mask">
            <div>Drop files or text here</div>
            <span>Text files will be attached with content preview.</span>
          </div>
        )}

        <div
          ref={messagesContainerRef}
          className="chat-panel__messages"
          onScroll={handleMessagesScroll}
        >
          {shouldRenderComplexComponents ? (
            <>
              {deferredMessages.length === 0 && !isLoading && (
                <div className="chat-panel__messages-empty">
                  <p>Start a task, then drag files or notes into the chat if needed.</p>
                </div>
              )}

              {deferredMessages.map((message) => (
                <div
                  key={message.id}
                  className={`chat-message chat-message--${message.role} chat-message--${message.source}`}
                >
                  <div className="chat-message__content">
                    {message.role === 'assistant' ? (
                      <ReactMarkdown>{message.content}</ReactMarkdown>
                    ) : (
                      message.content
                    )}
                  </div>
                  <div className="chat-message__meta">
                    {new Date(message.timestamp).toLocaleTimeString()}
                  </div>
                </div>
              ))}

              {isLoading && (
                <div className="chat-message chat-message--assistant">
                  <div className="chat-message__content chat-message__loading">
                    <span />
                    <span />
                    <span />
                  </div>
                </div>
              )}
            </>
          ) : (
            <div className="chat-panel__messages-placeholder">
              <p>Window is in {windowState} mode.</p>
              <p>Switch back to expanded mode to continue.</p>
            </div>
          )}
        </div>
      </div>

      {!hideInput && (
        <div className="chat-panel__input-wrap">
          <form
            className="chat-panel__input"
            onSubmit={(event) => {
              event.preventDefault();
              void submitInput();
            }}
          >
            <input
              type="text"
              value={input}
              onChange={(event) => setInput(event.target.value)}
              placeholder={wsStatus === 'connected' ? 'Type message, or drop files into chat area...' : 'Backend reconnecting...'}
              disabled={isLoading || isExecuting}
            />
            <button
              type="submit"
              disabled={!input.trim() || isLoading || isExecuting}
            >
              Send
            </button>
          </form>
        </div>
      )}
    </>
  );

  const renderPanelContent = () => {
    if (mode === 'chat-only') {
      return renderChatContent();
    }

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
      case 'skills':
        return <SkillsPanel onClose={() => setActivePanel('chat')} />;
      case 'scheduler':
        return <SchedulerPanel onClose={() => setActivePanel('chat')} />;
      case 'settings':
        return <SettingsPanel />;
      case 'chat':
      default:
        return renderChatContent();
    }
  };

  const rootClassName = mode === 'full' ? 'chat-panel' : 'chat-panel chat-panel--embedded';

  return (
    <div className={rootClassName}>
      {mode === 'full' && (
        <Sidebar
          activePanel={activePanel}
          onPanelChange={setActivePanel}
          isConnected={connected}
          isWorking={isWorking}
        />
      )}

      <div className="chat-panel__content">
        <div className="chat-panel__body">
          <div className="chat-panel__main">
            {renderPanelContent()}
          </div>
        </div>
      </div>
    </div>
  );
}
