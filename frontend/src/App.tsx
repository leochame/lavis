import { useState, useEffect } from 'react';
import { Capsule } from './components/Capsule';
import { ChatPanel } from './components/ChatPanel';
import { agentApi } from './api/agentApi';
import type { AgentStatus } from './types/agent';
import './App.css';

type ViewMode = 'capsule' | 'chat';

export default function App() {
  const [viewMode, setViewMode] = useState<ViewMode>('capsule');
  const [status, setStatus] = useState<AgentStatus | null>(null);

  // Start heartbeat on mount
  useEffect(() => {
    agentApi.startHeartbeat((newStatus) => {
      setStatus(newStatus);
    });

    return () => {
      agentApi.stopHeartbeat();
    };
  }, []);

  // Handle quick chat shortcut (Cmd+K)
  useEffect(() => {
    const handleQuickChat = () => {
      setViewMode('chat');
      // Resize window for chat mode
      if ((window as any).electronAPI?.resizeToChat) {
        (window as any).electronAPI.resizeToChat();
      }
    };

    // Listen for quick-chat event from Electron
    if ((window as any).electronAPI?.onQuickChat) {
      (window as any).electronAPI.onQuickChat(handleQuickChat);
    }
  }, []);

  const handleCapsuleClick = () => {
    setViewMode('chat');
    // Resize window for chat mode
    if ((window as any).electronAPI?.resizeToChat) {
      (window as any).electronAPI.resizeToChat();
    }
  };

  const handleChatClose = () => {
    setViewMode('capsule');
    // Resize window for capsule mode
    if ((window as any).electronAPI?.resizeToCapsule) {
      (window as any).electronAPI.resizeToCapsule();
    }
  };

  return (
    <div className="app">
      {viewMode === 'capsule' && (
        <Capsule status={status} onClick={handleCapsuleClick} />
      )}
      {viewMode === 'chat' && <ChatPanel onClose={handleChatClose} status={status} />}
    </div>
  );
}
