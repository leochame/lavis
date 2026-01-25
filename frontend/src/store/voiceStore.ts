import { create } from 'zustand';

export type VoiceStatus = 'idle' | 'listening' | 'processing' | 'speaking' | 'error';

interface VoiceState {
  status: VoiceStatus;
  transcript: string;
  agentResponse: string;
  agentAudio: string | null;
  isWakeWordActive: boolean;
  error: string | null;
}

interface VoiceActions {
  setStatus: (status: VoiceStatus) => void;
  setTranscript: (text: string) => void;
  setAgentResponse: (text: string) => void;
  setAgentAudio: (audio: string | null) => void;
  setWakeWordActive: (flag: boolean) => void;
  setError: (error: string | null) => void;
  reset: () => void;
}

const initialState: VoiceState = {
  status: 'idle',
  transcript: '',
  agentResponse: '',
  agentAudio: null,
  isWakeWordActive: false,
  error: null,
};

export const useVoiceStore = create<VoiceState & VoiceActions>((set) => ({
  ...initialState,
  setStatus: (status) => set({ status }),
  setTranscript: (text) => set({ transcript: text }),
  setAgentResponse: (text) => set({ agentResponse: text }),
  setAgentAudio: (audio) => set({ agentAudio: audio }),
  setWakeWordActive: (flag) => set({ isWakeWordActive: flag }),
  setError: (error) => set({ error }),
  reset: () => set(initialState),
}));

