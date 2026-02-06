import { useState, useEffect } from 'react';
import { useSettingsStore } from '../store/settingsStore';
import { Key, Check, X, Loader2, ExternalLink } from 'lucide-react';
import './SettingsPanel.css';

export function SettingsPanel() {
  const {
    apiKey,
    isConfigured,
    isLoading,
    error,
    setApiKey,
    clearApiKey,
    checkStatus,
    setError,
  } = useSettingsStore();

  const [inputKey, setInputKey] = useState('');
  const [showKey, setShowKey] = useState(false);

  // Check status on mount
  useEffect(() => {
    checkStatus();
  }, [checkStatus]);

  // Initialize input with stored key
  useEffect(() => {
    if (apiKey) {
      setInputKey(apiKey);
    }
  }, [apiKey]);

  const handleSave = async () => {
    if (!inputKey.trim()) {
      setError('Please enter an API key');
      return;
    }

    try {
      await setApiKey(inputKey.trim());
    } catch {
      // Error is already set in the store
    }
  };

  const handleClear = async () => {
    try {
      await clearApiKey();
      setInputKey('');
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
        <h2 className="settings-panel__title">Settings</h2>
        <p className="settings-panel__subtitle">Configure your Gemini API key</p>
      </div>

      <div className="settings-panel__content">
        <div className="settings-panel__section">
          <div className="settings-panel__section-header">
            <Key size={20} />
            <h3>Gemini API Key</h3>
            {isConfigured && (
              <span className="settings-panel__status settings-panel__status--configured">
                <Check size={14} />
                Configured
              </span>
            )}
            {!isConfigured && (
              <span className="settings-panel__status settings-panel__status--not-configured">
                <X size={14} />
                Not Configured
              </span>
            )}
          </div>

          <p className="settings-panel__description">
            Enter your Gemini API key to enable AI features. You can get a free API key from{' '}
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

          <div className="settings-panel__input-group">
            <input
              type={showKey ? 'text' : 'password'}
              className="settings-panel__input"
              placeholder="Enter your Gemini API key"
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

          {error && (
            <div className="settings-panel__error">
              <X size={14} />
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
                  <Loader2 size={16} className="settings-panel__spinner" />
                  Saving...
                </>
              ) : (
                <>
                  <Check size={16} />
                  Save
                </>
              )}
            </button>
            <button
              className="settings-panel__button settings-panel__button--secondary"
              onClick={handleClear}
              disabled={isLoading || !isConfigured}
            >
              <X size={16} />
              Clear
            </button>
          </div>
        </div>

        <div className="settings-panel__info">
          <h4>About API Key Storage</h4>
          <ul>
            <li>Your API key is stored locally in your browser</li>
            <li>It is also sent to the backend server for API calls</li>
            <li>The key is not persisted on the server (memory only)</li>
            <li>Clearing the key will require re-entering it after restart</li>
          </ul>
        </div>
      </div>
    </div>
  );
}
