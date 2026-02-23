import { useState, useEffect } from 'react';
import { useSettingsStore } from '../store/settingsStore';
import { Key, Check, X, Loader2, ExternalLink, Globe } from 'lucide-react';
import './SettingsPanel.css';

export function SettingsPanel() {
  const {
    apiKey,
    baseUrl,
    chatModelName,
    sttModelName,
    mode,
    isConfigured,
    isLoading,
    error,
    setConfig,
    clearConfig,
    checkStatus,
    setError,
  } = useSettingsStore();

  const [inputKey, setInputKey] = useState('');
  const [inputUrl, setInputUrl] = useState('');
  const [inputChatModel, setInputChatModel] = useState('');
  const [inputSttModel, setInputSttModel] = useState('');
  const [showKey, setShowKey] = useState(false);

  // Check status on mount
  useEffect(() => {
    checkStatus();
  }, [checkStatus]);

  // Initialize inputs with stored values
  useEffect(() => {
    if (apiKey) {
      setInputKey(apiKey);
    }
    if (baseUrl) {
      setInputUrl(baseUrl);
    }
    if (chatModelName) {
      setInputChatModel(chatModelName);
    }
    if (sttModelName) {
      setInputSttModel(sttModelName);
    }
  }, [apiKey, baseUrl, chatModelName, sttModelName]);

  const handleSave = async () => {
    if (!inputKey.trim()) {
      setError('Please enter an API key');
      return;
    }

    try {
      await setConfig(
        inputKey.trim(),
        inputUrl.trim() || undefined,
        inputChatModel.trim() || undefined,
        inputSttModel.trim() || undefined
      );
    } catch {
      // Error is already set in the store
    }
  };

  const handleClear = async () => {
    try {
      await clearConfig();
      setInputKey('');
      setInputUrl('');
      setInputChatModel('');
      setInputSttModel('');
    } catch {
      // Error is already set in the store
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !isLoading) {
      handleSave();
    }
  };

  return (
    <div className="settings-panel">
      <div className="settings-panel__header">
        <h2 className="settings-panel__title">API Console</h2>
        <p className="settings-panel__subtitle">Gemini access channel configuration</p>
      </div>

      <div className="settings-panel__content">
        <div className="settings-panel__section">
          <div className="settings-panel__section-header">
            <Key size={16} />
            <h3>API CHANNEL</h3>
            {isConfigured && (
              <span className={`settings-panel__status settings-panel__status--configured`}>
                <Check size={12} />
                {mode === 'proxy' ? 'Proxy Mode' : 'Official API'}
              </span>
            )}
            {!isConfigured && (
              <span className="settings-panel__status settings-panel__status--not-configured">
                <X size={12} />
                Not Configured
              </span>
            )}
          </div>

          <p className="settings-panel__description">
            Enter your Gemini API key used by Lavis console. You can get a free API key from{' '}
            <a
              href="https://aistudio.google.com/apikey"
              target="_blank"
              rel="noopener noreferrer"
              className="settings-panel__link"
            >
              Google AI Studio
              <ExternalLink size={12} />
            </a>
          </p>

          {/* API Key Input */}
          <div className="settings-panel__field">
            <label className="settings-panel__label">
              <Key size={14} />
              API KEY <span className="settings-panel__required">*</span>
            </label>
            <div className="settings-panel__input-group">
              <input
                type={showKey ? 'text' : 'password'}
                className="settings-panel__input"
                placeholder="sk-***************************"
                value={inputKey}
                onChange={(e) => setInputKey(e.target.value)}
                onKeyDown={handleKeyDown}
                disabled={isLoading}
              />
              <button
                className="settings-panel__toggle-visibility"
                onClick={() => setShowKey(!showKey)}
                title={showKey ? 'Hide API key' : 'Show API key'}
              >
                {showKey ? 'Hide' : 'Show'}
              </button>
            </div>
          </div>

          {/* Base URL Input */}
          <div className="settings-panel__field">
            <label className="settings-panel__label">
              <Globe size={14} />
              BASE URL <span className="settings-panel__optional">(optional – proxy / relay)</span>
            </label>
            <input
              type="text"
              className="settings-panel__input settings-panel__input--full-width"
              placeholder="Leave empty to use official Gemini endpoint"
              value={inputUrl}
              onChange={(e) => setInputUrl(e.target.value)}
              onKeyDown={handleKeyDown}
              disabled={isLoading}
            />
            <p className="settings-panel__hint">
              Leave empty to use Gemini official API. Fill in a custom URL to use a proxy/relay server.
            </p>
          </div>

          {/* Chat Model Name Input */}
          <div className="settings-panel__field">
            <label className="settings-panel__label">
              Chat MODEL NAME <span className="settings-panel__optional">(optional)</span>
            </label>
            <input
              type="text"
              className="settings-panel__input settings-panel__input--full-width"
              placeholder="e.g. gemini-2.0-flash, gemini-2.0-pro (default: gemini-3-flash-preview)"
              value={inputChatModel}
              onChange={(e) => setInputChatModel(e.target.value)}
              disabled={isLoading}
            />
          </div>

          {/* STT Model Name Input */}
          <div className="settings-panel__field">
            <label className="settings-panel__label">
              STT MODEL NAME <span className="settings-panel__optional">(optional)</span>
            </label>
            <input
              type="text"
              className="settings-panel__input settings-panel__input--full-width"
              placeholder="e.g. gemini-2.0-flash-audio (default: gemini-3-flash-preview)"
              value={inputSttModel}
              onChange={(e) => setInputSttModel(e.target.value)}
              disabled={isLoading}
            />
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
              onClick={handleSave}
              disabled={isLoading || !inputKey.trim()}
            >
              {isLoading ? (
                <>
                  <Loader2 size={14} className="settings-panel__spinner" />
                  Saving...
                </>
              ) : (
                <>
                  <Check size={14} />
                  Save
                </>
              )}
            </button>
            <button
              className="settings-panel__button settings-panel__button--secondary"
              onClick={handleClear}
              disabled={isLoading || !isConfigured}
            >
              <X size={14} />
              Clear
            </button>
          </div>
        </div>

        <div className="settings-panel__info">
          <h4>About API Modes</h4>
          <ul>
            <li><strong>Official API:</strong> Direct connection to Google Gemini API (recommended)</li>
            <li><strong>Proxy Mode:</strong> Use a custom relay server (for regions with restricted access)</li>
          </ul>
          <h4>Storage</h4>
          <ul>
            <li>Your configuration is stored locally in your browser</li>
            <li>It is also sent to the backend server for API calls</li>
            <li>The config is not persisted on the server (memory only)</li>
          </ul>
        </div>
      </div>
    </div>
  );
}
