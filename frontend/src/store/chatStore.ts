import { create } from 'zustand';

export type ChatMessageSource = 'text' | 'voice' | 'error';

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: number;
  source: ChatMessageSource;
}

const MAX_CACHED_MESSAGES = 120;

interface ChatState {
  input: string;
  messages: ChatMessage[];
  isLoading: boolean;
  isDropActive: boolean;
  dropHint: string | null;
  lastVoiceSignature: string;
}

interface ChatActions {
  setInput: (input: string) => void;
  setMessages: (messages: ChatMessage[]) => void;
  appendMessage: (message: ChatMessage) => void;
  appendMessages: (messages: ChatMessage[]) => void;
  clearMessages: () => void;
  setLoading: (loading: boolean) => void;
  setDropActive: (active: boolean) => void;
  setDropHint: (hint: string | null) => void;
  setLastVoiceSignature: (signature: string) => void;
}

export const useChatStore = create<ChatState & ChatActions>((set) => ({
  input: '',
  messages: [],
  isLoading: false,
  isDropActive: false,
  dropHint: null,
  lastVoiceSignature: '',
  setInput: (input) => set({ input }),
  setMessages: (messages) => set({ messages: messages.slice(-MAX_CACHED_MESSAGES) }),
  appendMessage: (message) =>
    set((state) => ({ messages: [...state.messages, message].slice(-MAX_CACHED_MESSAGES) })),
  appendMessages: (messages) =>
    set((state) => ({ messages: [...state.messages, ...messages].slice(-MAX_CACHED_MESSAGES) })),
  clearMessages: () => set({ messages: [], input: '' }),
  setLoading: (isLoading) => set({ isLoading }),
  setDropActive: (isDropActive) => set({ isDropActive }),
  setDropHint: (dropHint) => set({ dropHint }),
  setLastVoiceSignature: (lastVoiceSignature) => set({ lastVoiceSignature }),
}));
