import { useEffect } from 'react';

interface UseAutoRecordListenerParams {
  setViewMode: (mode: 'capsule' | 'chat') => void;
  startRecording?: () => void;
}

/**
 * 监听自动录音事件，用于启动后自动进入聊天并开始录音。
 */
export function useAutoRecordListener({
  setViewMode,
  startRecording,
}: UseAutoRecordListenerParams) {
  useEffect(() => {
    const handleAutoRecord = () => {
      setViewMode('chat');
      setTimeout(() => {
        startRecording?.();
      }, 500);
    };

    window.addEventListener('lavis-auto-record', handleAutoRecord);
    return () => {
      window.removeEventListener('lavis-auto-record', handleAutoRecord);
    };
  }, [setViewMode, startRecording]);
}
