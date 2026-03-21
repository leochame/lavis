import { useEffect } from 'react';
import { useSettingsStore } from '../store/settingsStore';
import { Key, Check, X, Loader2, RefreshCw, FileCode2, Globe } from 'lucide-react';
import './SettingsPanel.css';

const ENV_TEMPLATE = `# .env (project root)
app.llm.models.fast-model.api-key=your_chat_key
app.llm.models.fast-model.base-url=
app.llm.models.fast-model.model-name=gemini-3-flash-preview

app.llm.models.whisper.api-key=your_stt_key
app.llm.models.whisper.base-url=
app.llm.models.whisper.model-name=gemini-3-flash-preview

app.llm.models.tts.api-key=your_tts_key
app.llm.models.tts.model-name=gemini-2.5-flash-preview-tts
app.llm.models.tts.voice=Kore
app.llm.models.tts.format=wav`;

export function SettingsPanel() {
  const {
    baseUrl,
    mode,
    isConfigured,
    isLoading,
    error,
    source,
    chatConfigured,
    sttConfigured,
    ttsConfigured,
    checkStatus,
  } = useSettingsStore();

  useEffect(() => {
    checkStatus();
  }, [checkStatus]);

  return (
    <div className="settings-panel">
      <div className="settings-panel__header">
        <h2 className="settings-panel__title">Backend Config</h2>
        <p className="settings-panel__subtitle">API keys and model settings are loaded from backend .env</p>
      </div>

      <div className="settings-panel__content">
        <div className="settings-panel__section">
          <div className="settings-panel__section-header">
            <Key size={16} />
            <h3>CONFIG STATUS</h3>
            {isConfigured ? (
              <span className="settings-panel__status settings-panel__status--configured">
                <Check size={12} />
                {mode === 'proxy' ? 'Proxy Mode' : 'Official API'}
              </span>
            ) : (
              <span className="settings-panel__status settings-panel__status--not-configured">
                <X size={12} />
                Not Configured
              </span>
            )}
          </div>

          <p className="settings-panel__description">
            Frontend no longer uploads API keys. Please edit the project root <code>.env</code> file and restart
            backend.
          </p>

          <div className="settings-panel__field">
            <label className="settings-panel__label">
              <Globe size={14} />
              SOURCE
            </label>
            <p className="settings-panel__hint">{source === 'env' || !source ? '.env / local backend config' : source}</p>
          </div>

          <div className="settings-panel__field">
            <label className="settings-panel__label">
              <Globe size={14} />
              CHAT BASE URL
            </label>
            <p className="settings-panel__hint">{baseUrl || 'Using official endpoint (no custom base-url)'}</p>
          </div>

          <div className="settings-panel__field">
            <label className="settings-panel__label">
              <Check size={14} />
              MODEL CHECKS
            </label>
            <ul className="settings-panel__status-list">
              <li>Chat: {chatConfigured ? 'configured' : 'missing'}</li>
              <li>STT: {sttConfigured ? 'configured' : 'missing'}</li>
              <li>TTS: {ttsConfigured ? 'configured' : 'missing'}</li>
            </ul>
          </div>

          {error && (
            <div className="settings-panel__error">
              <X size={12} />
              {error}
            </div>
          )}

          <div className="settings-panel__actions">
            <button
              className="settings-panel__button settings-panel__button--primary"
              onClick={() => checkStatus()}
              disabled={isLoading}
            >
              {isLoading ? (
                <>
                  <Loader2 size={14} className="settings-panel__spinner" />
                  Checking...
                </>
              ) : (
                <>
                  <RefreshCw size={14} />
                  Refresh Status
                </>
              )}
            </button>
          </div>
        </div>

        <div className="settings-panel__info">
          <h4>How To Configure</h4>
          <ul>
            <li>Copy root file <code>.env.example</code> to <code>.env</code></li>
            <li>Fill API keys / base URL / model names in <code>.env</code></li>
            <li>Restart backend process after editing</li>
          </ul>
          <h4>Sample .env</h4>
          <div className="settings-panel__code-block">
            <div className="settings-panel__code-header">
              <FileCode2 size={14} />
              <span>.env</span>
            </div>
            <pre className="settings-panel__code">{ENV_TEMPLATE}</pre>
          </div>
        </div>
      </div>
    </div>
  );
}
