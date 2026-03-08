import type { FormEvent } from 'react';
import { Mic, MicOff } from 'lucide-react';

interface FloatingInputBarProps {
  value: string;
  onChange: (value: string) => void;
  onSubmit: () => void;
  disabled?: boolean;
  placeholder?: string;
  /** 语音相关功能 */
  isRecording?: boolean;
  onMicClick?: () => void;
  /** 是否显示快捷键提示 */
  showShortcuts?: boolean;
}

export function FloatingInputBar({
  value,
  onChange,
  onSubmit,
  disabled,
  placeholder,
  isRecording = false,
  onMicClick,
  showShortcuts = true,
}: FloatingInputBarProps) {
  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!disabled && value.trim()) {
      onSubmit();
    }
  };

  const handleMicClick = () => {
    if (onMicClick) {
      onMicClick();
    }
  };

  return (
    <div className="pointer-events-none fixed inset-x-0 bottom-6 flex justify-center">
      <div className="pointer-events-auto w-full max-w-3xl px-4">
        <form
          onSubmit={handleSubmit}
          className={[
            'flex items-center gap-2 rounded-2xl bg-slate-950/90 px-4 py-2.5',
            'backdrop-blur-2xl border border-white/15 shadow-[0_22px_70px_rgba(15,23,42,0.95)]',
            'transition-all duration-150 ease-out',
            'hover:border-amber-300/80 hover:shadow-[0_30px_90px_rgba(251,191,36,0.7)]',
            'focus-within:border-amber-300/90 focus-within:outline-none',
          ].join(' ')}
        >
          {/* 左侧快捷键提示 */}
          {showShortcuts && (
            <div className="hidden items-center gap-1 text-[11px] text-slate-300 sm:flex">
              <span className="rounded-full border border-white/20 bg-white/10 px-2 py-0.5 shadow-[0_0_14px_rgba(248,250,252,0.8)]">
                ⌘K
              </span>
              <span className="text-slate-500">or</span>
              <span className="rounded-full border border-white/15 bg-slate-900/80 px-2 py-0.5">
                Enter
              </span>
            </div>
          )}

          {/* 语音按钮 */}
          {onMicClick && (
            <button
              type="button"
              onClick={handleMicClick}
              disabled={disabled}
              className={[
                'flex items-center justify-center rounded-xl p-1.5 transition-colors duration-150',
                isRecording
                  ? 'bg-red-500/20 text-red-300 border border-red-400/60 shadow-[0_0_18px_rgba(248,113,113,0.75)]'
                  : 'bg-white/5 text-slate-300 hover:bg-white/10 hover:text-slate-50 border border-white/15',
                disabled && 'opacity-50 cursor-not-allowed',
              ].join(' ')}
              title={isRecording ? 'Stop recording' : 'Start voice recording'}
            >
              {isRecording ? (
                <MicOff className="h-4 w-4" strokeWidth={1.5} />
              ) : (
                <Mic className="h-4 w-4" strokeWidth={1.5} />
              )}
            </button>
          )}

          {/* 输入框 */}
          <input
            type="text"
            value={value}
            onChange={(e) => onChange(e.target.value)}
            placeholder={placeholder || 'Type a message...'}
            disabled={disabled}
            className="flex-1 bg-transparent text-sm text-slate-100 placeholder:text-slate-500 outline-none border-none focus:ring-0"
          />

          {/* 发送按钮 */}
          <button
            type="submit"
            disabled={disabled || !value.trim()}
            className="rounded-xl bg-gradient-to-r from-rose-400 via-amber-300 to-emerald-300 px-3 py-1.5 text-xs font-semibold uppercase tracking-[0.16em] text-slate-950 shadow-[0_0_22px_rgba(251,191,36,0.8)] transition-all duration-150 hover:brightness-110 hover:-translate-y-[1px] disabled:cursor-not-allowed disabled:bg-slate-700 disabled:text-slate-400 disabled:shadow-none disabled:translate-y-0"
          >
            Send
          </button>
        </form>
      </div>
    </div>
  );
}


