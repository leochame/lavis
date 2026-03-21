interface StartOverlayProps {
  onMicStart: () => void;
}

export function StartOverlay({ onMicStart }: StartOverlayProps) {
  const hasPicoKey = !!import.meta.env.VITE_PICOVOICE_KEY;
  const hasWakeWordPath =
    !!import.meta.env.VITE_WAKE_WORD_PATH || !!import.meta.env.VITE_WAKE_WORD_BASE64;

  return (
    <div className="start-overlay">
      <div className="start-overlay__content">
        <div className="start-overlay__capsule">
          <div className="capsule capsule--idle capsule--breathing">
            <div className="capsule__core"></div>
            <div className="capsule__glow"></div>
          </div>
        </div>
        <h1>Lavis AI</h1>
        <p className="start-overlay__subtitle">Your Local AI Assistant</p>

        {!hasPicoKey && (
          <div className="start-overlay__warning">
            <p>Missing Picovoice Access Key</p>
            <p className="start-overlay__warning-detail">
              Voice wake word feature requires Picovoice Access Key
            </p>
            <p className="start-overlay__warning-detail">
              Please add to <code>.env.local</code> file:
            </p>
            <pre className="start-overlay__code">VITE_PICOVOICE_KEY=your_access_key_here</pre>
            <p className="start-overlay__warning-detail">
              <a href="https://console.picovoice.ai/" target="_blank" rel="noopener noreferrer">
                Visit Picovoice Console to get a free Access Key
              </a>
            </p>
          </div>
        )}

        {hasPicoKey && !hasWakeWordPath && (
          <div className="start-overlay__warning">
            <p>Missing Wake Word Model</p>
            <p className="start-overlay__warning-detail">
              Please configure wake word model path or Base64 encoding in <code>.env.local</code>:
            </p>
            <pre className="start-overlay__code">
              VITE_WAKE_WORD_PATH=/hi-lavis.ppn
              {'\n'}# or
              {'\n'}VITE_WAKE_WORD_BASE64=&lt;base64 encoded .ppn file&gt;
            </pre>
          </div>
        )}

        <button
          className="start-overlay__mic-button"
          onClick={onMicStart}
          title="Click to start conversation"
        >
          <svg className="start-overlay__mic-icon" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
            <path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3z" />
            <path d="M17 11c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z" />
          </svg>
        </button>

        <p className="start-overlay__mic-hint">
          {hasPicoKey
            ? 'Click microphone to start conversation'
            : 'Click microphone to start conversation (wake word not configured, voice wake-up unavailable)'}
        </p>

        <p className="start-overlay__hint">
          {hasPicoKey && hasWakeWordPath
            ? 'Will automatically enter voice conversation mode after clicking'
            : 'Manual voice conversation still available without Picovoice wake word (click microphone to start)'}
        </p>
      </div>
    </div>
  );
}
