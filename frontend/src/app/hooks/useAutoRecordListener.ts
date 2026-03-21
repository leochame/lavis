import { useEffect } from 'react';

interface UseAutoRecordListenerParams {
  setViewMode: (mode: 'capsule' | 'chat') => void;
  setWindowState: (state: 'idle' | 'listening' | 'expanded') => void;
  startRecording?: () => void;
}

/**
 * 监听自动录音事件，用于启动后自动进入聊天并开始录音。
 */
export function useAutoRecordListener({
  setViewMode,
  setWindowState,
  startRecording,
}: UseAutoRecordListenerParams) {
  useEffect(() => {
    const handleAutoRecord = () => {
      setViewMode('chat');
      setWindowState('expanded');
      setTimeout(() => {
        startRecording?.();
      }, 500);
    };

    window.addEventListener('lavis-auto-record', handleAutoRecord);
    return () => {
      window.removeEventListener('lavis-auto-record', handleAutoRecord);
    };
  }, [setViewMode, setWindowState, startRecording]);
}
