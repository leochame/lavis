import { startTransition, useCallback, useDeferredValue, useEffect, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import { agentApi } from '../api/agentApi';
import { managementApi, type CreateTaskRequest, type TaskInterpretDraft } from '../api/managementApi';
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

const AGENT_MESSAGE_CACHE_KEY = 'lavis_chat_messages_agent_v1';
const TASK_MESSAGE_CACHE_KEY = 'lavis_chat_messages_task_v1';
const MAX_CACHED_MESSAGES = 120;
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

function createTaskMemoryKey(): string {
  const random = Math.random().toString(36).slice(2, 10);
  return `task-flow-${Date.now()}-${random}`;
}

function loadCachedMessages(cacheKey: string): ChatMessage[] {
  if (typeof window === 'undefined') return [];

  try {
    const raw = window.sessionStorage.getItem(cacheKey);
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

type ChatInputMode = 'chat' | 'task';
type TaskMissingField = 'schedule' | 'requestContent';

interface TaskDraft {
  name?: string;
  scheduleMode?: 'CRON' | 'LOOP';
  cronExpression?: string;
  intervalSeconds?: number;
  requestContent?: string;
  requestUseOrchestrator: boolean;
  enabled: boolean;
}

function toTaskDraft(draft: TaskInterpretDraft | null | undefined): TaskDraft | null {
  if (!draft) {
    return null;
  }
  return {
    name: draft.name,
    scheduleMode: draft.scheduleMode,
    cronExpression: draft.cronExpression,
    intervalSeconds: draft.intervalSeconds,
    requestContent: draft.requestContent,
    requestUseOrchestrator: draft.requestUseOrchestrator ?? true,
    enabled: draft.enabled ?? true,
  };
}

function isTaskFlowCancel(text: string): boolean {
  const normalized = text.trim().toLowerCase();
  return normalized === 'cancel' || normalized === 'exit task mode';
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
  const agentInput = useChatStore((s) => s.input);
  const setAgentInput = useChatStore((s) => s.setInput);
  const agentMessages = useChatStore((s) => s.messages);
  const setAgentMessages = useChatStore((s) => s.setMessages);
  const appendAgentMessage = useChatStore((s) => s.appendMessage);
  const appendAgentMessagesBatch = useChatStore((s) => s.appendMessages);
  const clearChatStore = useChatStore((s) => s.clearMessages);
  const agentLoading = useChatStore((s) => s.isLoading);
  const setAgentLoading = useChatStore((s) => s.setLoading);
  const isDropActive = useChatStore((s) => s.isDropActive);
  const setDropActive = useChatStore((s) => s.setDropActive);
  const dropHint = useChatStore((s) => s.dropHint);
  const setDropHint = useChatStore((s) => s.setDropHint);
  const lastVoiceSignature = useChatStore((s) => s.lastVoiceSignature);
  const setLastVoiceSignature = useChatStore((s) => s.setLastVoiceSignature);
  const [activePanel, setActivePanel] = useState<PanelType>('chat');
  const [inputMode, setInputMode] = useState<ChatInputMode>('chat');
  const [taskInput, setTaskInput] = useState('');
  const [taskMessages, setTaskMessages] = useState<ChatMessage[]>([]);
  const [taskLoading, setTaskLoading] = useState(false);
  const [pendingTaskDraft, setPendingTaskDraft] = useState<TaskDraft | null>(null);
  const [taskMissingField, setTaskMissingField] = useState<TaskMissingField | null>(null);
  const [taskMemoryKey, setTaskMemoryKey] = useState<string>(() => createTaskMemoryKey());
  const messagesContainerRef = useRef<HTMLDivElement>(null);
  const shouldStickToBottomRef = useRef(true);
  const dragDepthRef = useRef(0);
  const dropHintTimerRef = useRef<number | null>(null);
  const hasHydratedRef = useRef(false);

  const windowState = useUIStore((s) => s.windowState);
  const isEmbeddedMode = mode === 'chat-only';
  const shouldRenderComplexComponents = isEmbeddedMode || windowState === 'expanded';
  const isTaskMode = inputMode === 'task';
  const isExecuting = workflow.status === 'executing' || Boolean(status?.orchestrator_state?.includes('EXECUTING'));
  const isPlanning = workflow.status === 'planning' || Boolean(status?.orchestrator_state?.includes('PLANNING'));
  const currentInput = isTaskMode ? taskInput : agentInput;
  const currentMessages = isTaskMode ? taskMessages : agentMessages;
  const currentLoading = isTaskMode ? taskLoading : agentLoading;
  const deferredMessages = useDeferredValue(currentMessages);
  const isWorking = isExecuting || isPlanning || agentLoading || taskLoading;
  const inputDisabled = currentLoading || (!isTaskMode && isExecuting);
  const submitDisabled = !currentInput.trim() || inputDisabled;

  useEffect(() => {
    if (hasHydratedRef.current) return;
    hasHydratedRef.current = true;
    if (agentMessages.length === 0) {
      const cachedAgent = loadCachedMessages(AGENT_MESSAGE_CACHE_KEY);
      if (cachedAgent.length > 0) {
        setAgentMessages(cachedAgent);
      }
    }
    if (taskMessages.length === 0) {
      const cachedTask = loadCachedMessages(TASK_MESSAGE_CACHE_KEY);
      if (cachedTask.length > 0) {
        setTaskMessages(cachedTask);
      }
    }
  }, [agentMessages.length, setAgentMessages, taskMessages.length]);

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
      appendAgentMessagesBatch(incoming);
    });
  }, [appendAgentMessagesBatch]);

  const appendTaskMessage = useCallback((message: ChatMessage) => {
    setTaskMessages((state) => [...state, message].slice(-MAX_CACHED_MESSAGES));
  }, []);

  const scrollToBottom = useCallback((behavior: ScrollBehavior = 'auto') => {
    const container = messagesContainerRef.current;
    if (!container) return;
    container.scrollTo({ top: container.scrollHeight, behavior });
  }, []);

  const clearMessages = useCallback(() => {
    if (isTaskMode) {
      setTaskInput('');
      setTaskMessages([]);
      setTaskLoading(false);
      setPendingTaskDraft(null);
      setTaskMissingField(null);
      setTaskMemoryKey(createTaskMemoryKey());
    } else {
      clearChatStore();
    }
    if (typeof window !== 'undefined') {
      window.sessionStorage.removeItem(isTaskMode ? TASK_MESSAGE_CACHE_KEY : AGENT_MESSAGE_CACHE_KEY);
    }
  }, [clearChatStore, isTaskMode]);

  const switchInputMode = useCallback((nextMode: ChatInputMode) => {
    if (nextMode === inputMode) return;
    setInputMode(nextMode);
    setPendingTaskDraft(null);
    setTaskMissingField(null);
    setTaskMemoryKey(createTaskMemoryKey());
    const switchMessage: ChatMessage = {
      id: createMessageId('assistant'),
      role: 'assistant',
      content: nextMode === 'task'
        ? 'Switched to Task Mode. You can say "Remind me to write a daily report at 9:30 AM" or "Check service status every 30 minutes". I will ask follow-up questions if details are missing.'
        : 'Switched back to Chat Mode.',
      timestamp: Date.now(),
      source: 'text',
    };
    if (nextMode === 'task') {
      setTaskMessages((state) => [...state, switchMessage].slice(-MAX_CACHED_MESSAGES));
    } else {
      appendAgentMessage(switchMessage);
    }
  }, [appendAgentMessage, inputMode]);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    window.sessionStorage.setItem(AGENT_MESSAGE_CACHE_KEY, JSON.stringify(agentMessages));
  }, [agentMessages]);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    window.sessionStorage.setItem(TASK_MESSAGE_CACHE_KEY, JSON.stringify(taskMessages));
  }, [taskMessages]);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    if (!shouldStickToBottomRef.current) return;

    const rafId = window.requestAnimationFrame(() => {
      scrollToBottom(currentLoading ? 'auto' : 'smooth');
    });
    return () => window.cancelAnimationFrame(rafId);
  }, [deferredMessages.length, currentLoading, scrollToBottom]);

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
    if (inputMode !== 'chat') return;
    if (
      (workflow.status === 'executing' || workflow.status === 'planning') &&
      workflow.steps.length > 0
    ) {
      const timer = window.setTimeout(() => setActivePanel('brain'), 900);
      return () => window.clearTimeout(timer);
    }
  }, [activePanel, inputMode, mode, workflow.status, workflow.steps.length]);

  const submitInput = useCallback(async () => {
    const messageText = currentInput.trim();
    if (!messageText || currentLoading) return;

    const timestamp = Date.now();
    const userMessage: ChatMessage = {
      id: createMessageId('user'),
      role: 'user',
      content: messageText,
      timestamp,
      source: 'text',
    };
    if (isTaskMode) {
      appendTaskMessage(userMessage);
    } else {
      appendAgentMessage(userMessage);
    }

    if (isTaskMode) {
      setTaskInput('');
      setTaskLoading(true);
    } else {
      setAgentInput('');
      setAgentLoading(true);
    }
    try {
      if (inputMode === 'task') {
        if (isTaskFlowCancel(messageText)) {
          setPendingTaskDraft(null);
          setTaskMissingField(null);
          setTaskMemoryKey(createTaskMemoryKey());
          appendTaskMessage({
            id: createMessageId('assistant'),
            role: 'assistant',
            content: 'Current task creation flow canceled. You can describe a new scheduled task.',
            timestamp: timestamp + 1,
            source: 'text',
          });
          return;
        }

        let payload: CreateTaskRequest | null = null;

        const interpreted = await managementApi.interpretScheduledTask({
          text: messageText,
          draft: pendingTaskDraft ?? undefined,
          memoryKey: taskMemoryKey,
        });

        setPendingTaskDraft(toTaskDraft(interpreted.draft));
        setTaskMissingField(interpreted.missingField);

        if (!interpreted.ready || !interpreted.task) {
          appendTaskMessage({
            id: createMessageId('assistant'),
            role: 'assistant',
            content: interpreted.message || 'Task details are incomplete. Please provide the missing information.',
            timestamp: timestamp + 1,
            source: 'text',
          });
          return;
        }

        payload = interpreted.task;

        const created = await managementApi.createScheduledTask(payload);
        setPendingTaskDraft(null);
        setTaskMissingField(null);
        setTaskMemoryKey(createTaskMemoryKey());
        appendTaskMessage({
          id: createMessageId('assistant'),
          role: 'assistant',
          content: [
            `Task created successfully: **${created.name}**`,
            `- Schedule: ${created.scheduleMode === 'LOOP' ? `every ${created.intervalSeconds}s` : created.cronExpression}`,
            `- Content: ${created.requestContent ?? '(empty)'}`,
            `- Status: ${created.enabled ? 'enabled' : 'disabled'}`,
          ].join('\n'),
          timestamp: timestamp + 1,
          source: 'text',
        });
      } else {
        resetWorkflow();
        if (connected) {
          sendMessage('subscribe', {});
          sendMessage('ping', {});
        }

        const response = await agentApi.chat({ message: messageText });
        appendAgentMessage({
          id: createMessageId('assistant'),
          role: 'assistant',
          content: response.agent_text || response.response,
          timestamp: timestamp + 1,
          source: 'text',
        });
      }
    } catch (error) {
      const errorMessage: ChatMessage = {
        id: createMessageId('error'),
        role: 'assistant',
        content: `Error: ${(error as Error).message}`,
        timestamp: Date.now(),
        source: 'error',
      };
      if (isTaskMode) {
        setTaskMessages((state) => [...state, errorMessage].slice(-MAX_CACHED_MESSAGES));
      } else {
        appendAgentMessage(errorMessage);
      }
    } finally {
      if (isTaskMode) {
        setTaskLoading(false);
      } else {
        setAgentLoading(false);
      }
    }
  }, [
    appendTaskMessage,
    appendAgentMessage,
    connected,
    currentInput,
    currentLoading,
    inputMode,
    isTaskMode,
    pendingTaskDraft,
    taskMemoryKey,
    resetWorkflow,
    sendMessage,
    setAgentInput,
    setAgentLoading,
  ]);

  useEffect(() => {
    if (!onBindInput) return;
    onBindInput({
      value: currentInput,
      onChange: (value: string) => {
        if (isTaskMode) {
          setTaskInput(value);
        } else {
          setAgentInput(value);
        }
      },
      onSubmit: () => {
        void submitInput();
      },
      disabled: inputDisabled,
      placeholder: wsStatus !== 'connected'
        ? 'Backend reconnecting...'
        : inputMode === 'task'
          ? 'Task mode: e.g. "Remind me to write a daily report at 9:30 AM"'
          : 'Type a message, or drop files here...',
    });
  }, [onBindInput, currentInput, inputMode, inputDisabled, isTaskMode, setAgentInput, submitInput, wsStatus]);

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
      setTransientHint('No usable dropped content detected.');
      return;
    }

    const payload = `Please use the following dropped context:\n\n${sections.join('\n\n')}`;
    const existingInput = isTaskMode ? taskInput : useChatStore.getState().input;
    const nextInput = existingInput ? `${existingInput}\n\n${payload}` : payload;
    if (isTaskMode) {
      setTaskInput(nextInput);
    } else {
      setAgentInput(nextInput);
    }

    if (files.length > 0 && droppedText) {
      setTransientHint(`Attached ${files.length} file(s) and text snippet.`);
      return;
    }
    if (files.length > 0) {
      setTransientHint(`Attached ${files.length} file(s) into input.`);
      return;
    }
    setTransientHint('Attached dropped text into input.');
  }, [isTaskMode, setAgentInput, setTransientHint, taskInput]);

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
          <div className="chat-panel__mode-switch" role="tablist" aria-label="Input mode">
            <button
              type="button"
              role="tab"
              aria-selected={inputMode === 'chat'}
              className={`chat-panel__mode-tab ${inputMode === 'chat' ? 'chat-panel__mode-tab--active' : ''}`}
              onClick={() => switchInputMode('chat')}
            >
              Chat Mode
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={inputMode === 'task'}
              className={`chat-panel__mode-tab ${inputMode === 'task' ? 'chat-panel__mode-tab--active' : ''}`}
              onClick={() => switchInputMode('task')}
            >
              Task Mode
            </button>
          </div>
          <span className={`chat-panel__mode-hint ${inputMode === 'task' ? 'chat-panel__mode-hint--task' : 'chat-panel__mode-hint--chat'}`}>
            {inputMode === 'task' ? 'Plan and schedule automations' : 'Fast conversation and Q&A'}
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
            <span>Text files will be attached with a content preview.</span>
          </div>
        )}

        <div
          ref={messagesContainerRef}
          className="chat-panel__messages"
          onScroll={handleMessagesScroll}
        >
          {shouldRenderComplexComponents ? (
            <>
              {deferredMessages.length === 0 && !currentLoading && (
                <div className="chat-panel__messages-empty">
                  {inputMode === 'task' ? (
                    <>
                      <p>Task Mode is on. Describe your scheduling goal and I will fill missing fields.</p>
                      <p>Example: "Remind me to write a daily report at 9:30 AM".</p>
                    </>
                  ) : (
                    <p>Start a conversation, then drag files or notes into the chat if needed.</p>
                  )}
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

              {currentLoading && (
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
              <p>Window is currently in {windowState} mode.</p>
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
              value={currentInput}
              onChange={(event) => {
                if (isTaskMode) {
                  setTaskInput(event.target.value);
                } else {
                  setAgentInput(event.target.value);
                }
              }}
              placeholder={
                wsStatus !== 'connected'
                  ? 'Backend reconnecting...'
                  : inputMode === 'task'
                    ? taskMissingField === 'schedule'
                      ? 'Please provide a schedule, e.g. "Every day at 9:30 AM"'
                      : taskMissingField === 'requestContent'
                        ? 'Please provide task content, e.g. "Remind me to write a daily report"'
                        : 'Task mode: e.g. "Remind me to write a daily report at 9:30 AM"'
                    : 'Type a message, or drop files into the chat area...'
              }
              disabled={inputDisabled}
            />
            <button
              type="submit"
              disabled={submitDisabled}
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
