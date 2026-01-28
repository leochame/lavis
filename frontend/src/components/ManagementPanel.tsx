import './ManagementPanel.css';

interface ManagementPanelProps {
  onClose?: () => void;
}

export function ManagementPanel({ onClose }: ManagementPanelProps) {
  return (
    <div className="management-panel">
      <div className="management-panel__header">
        <div className="management-panel__title">
          <span className="management-panel__title-text">Studio</span>
          <span className="management-panel__title-sub">System settings</span>
        </div>
        {onClose && (
          <button className="management-panel__close" onClick={onClose}>×</button>
        )}
      </div>

      <div className="management-panel__content">
        <div className="management-panel__section">
          <div className="management-panel__row">
            <div className="management-panel__row-left">
              <div className="management-panel__row-title">LAVIS Studio</div>
              <div className="management-panel__row-desc">
                Skills / Scheduler are now first-class panels in the sidebar to avoid duplication.
              </div>
            </div>
          </div>
          <div className="management-panel__hint">
            Use the left sidebar to open <strong>Skills</strong> or <strong>Schedule</strong>.
          </div>
        </div>
      </div>
    </div>
  );
}
