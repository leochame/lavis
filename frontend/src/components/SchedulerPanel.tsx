import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  managementApi,
  type CreateTaskRequest,
  type ScheduledTask,
  type TaskRunLog,
  type UpdateTaskRequest,
} from '../api/managementApi';
import './SchedulerPanel.css';

interface SchedulerPanelProps {
  onClose?: () => void;
  className?: string;
  showHeader?: boolean;
}

type ViewMode = 'list' | 'create' | 'edit';

interface FormState {
  name: string;
  description: string;
  scheduleMode: 'CRON' | 'LOOP';
  cronExpression: string;
  intervalSeconds: string;
  executionMode: 'COMMAND' | 'REQUEST';
  command: string;
  requestContent: string;
  requestUseOrchestrator: boolean;
  enabled: boolean;
}

const POLL_INTERVAL_MS = 4000;
const DEFAULT_CRON = '0 9 * * 1-5';
const DEFAULT_LOOP_SECONDS = '300';

const TASK_FILE_SAMPLE = `---
id: daily-brief
enabled: true
cron: "0 9 * * 1-5"
mode: request
---
Summarize today's priorities in 3 bullets.`;

const DEFAULT_FORM: FormState = {
  name: '',
  description: '',
  scheduleMode: 'CRON',
  cronExpression: DEFAULT_CRON,
  intervalSeconds: DEFAULT_LOOP_SECONDS,
  executionMode: 'REQUEST',
  command: '',
  requestContent: '',
  requestUseOrchestrator: true,
  enabled: true,
};

function createEmptyForm(): FormState {
  return { ...DEFAULT_FORM };
}

function sortTasks(tasks: ScheduledTask[]): ScheduledTask[] {
  return [...tasks].sort((left, right) => {
    if (left.sourceType !== right.sourceType) {
      return left.sourceType === 'FILE' ? -1 : 1;
    }
    if (left.enabled !== right.enabled) {
      return left.enabled ? -1 : 1;
    }
    const leftNext = left.nextRunAt ?? '9999-12-31T23:59:59';
    const rightNext = right.nextRunAt ?? '9999-12-31T23:59:59';
    if (leftNext !== rightNext) {
      return leftNext.localeCompare(rightNext);
    }
    return left.name.localeCompare(right.name);
  });
}

function formatDateTime(value: string | null): string {
  if (!value) {
    return 'Not scheduled';
  }

  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return value;
  }

  return parsed.toLocaleString();
}

function truncate(value: string | null, maxLength = 140): string | null {
  if (!value) {
    return null;
  }
  if (value.length <= maxLength) {
    return value;
  }
  return `${value.slice(0, maxLength)}...`;
}

function toFormState(task: ScheduledTask): FormState {
  return {
    name: task.name,
    description: task.description ?? '',
    scheduleMode: task.scheduleMode,
    cronExpression: task.cronExpression ?? DEFAULT_CRON,
    intervalSeconds: task.intervalSeconds ? String(task.intervalSeconds) : DEFAULT_LOOP_SECONDS,
    executionMode: task.executionMode,
    command: task.command ?? '',
    requestContent: task.requestContent ?? '',
    requestUseOrchestrator: task.requestUseOrchestrator,
    enabled: task.enabled,
  };
}

function toRequestPayload(
  form: FormState,
  options?: { forUpdate?: boolean }
): CreateTaskRequest | UpdateTaskRequest {
  const forUpdate = options?.forUpdate ?? false;
  const scheduleMode = form.scheduleMode;
  const executionMode = form.executionMode;
  const intervalSeconds = Number.parseInt(form.intervalSeconds, 10);
  const description = form.description.trim();
  const command = form.command.trim();
  const requestContent = form.requestContent.trim();

  return {
    name: form.name.trim(),
    description: forUpdate ? description : description || undefined,
    scheduleMode,
    cronExpression: scheduleMode === 'CRON' ? form.cronExpression.trim() : undefined,
    intervalSeconds: scheduleMode === 'LOOP' ? intervalSeconds : undefined,
    executionMode,
    command:
      executionMode === 'COMMAND'
        ? command
        : forUpdate
          ? ''
          : undefined,
    requestContent:
      executionMode === 'REQUEST'
        ? requestContent
        : forUpdate
          ? ''
          : undefined,
    requestUseOrchestrator: executionMode === 'REQUEST' ? form.requestUseOrchestrator : false,
    enabled: form.enabled,
  };
}

function validateForm(form: FormState): string | null {
  if (!form.name.trim()) {
    return 'Task name is required.';
  }

  if (form.scheduleMode === 'CRON' && !form.cronExpression.trim()) {
    return 'Cron expression is required in CRON mode.';
  }

  if (form.scheduleMode === 'LOOP') {
    const seconds = Number.parseInt(form.intervalSeconds, 10);
    if (!Number.isFinite(seconds) || seconds <= 0) {
      return 'Loop interval must be a positive number of seconds.';
    }
  }

  if (form.executionMode === 'REQUEST' && !form.requestContent.trim()) {
    return 'Request content is required in REQUEST mode.';
  }

  if (form.executionMode === 'COMMAND' && !form.command.trim()) {
    return 'Command content is required in COMMAND mode.';
  }

  return null;
}

function statusTone(status: string | null): string {
  const normalized = status?.toUpperCase() ?? '';
  if (normalized === 'SUCCESS') {
    return 'scheduler-panel__status scheduler-panel__status--success';
  }
  if (
    normalized === 'FAILED' ||
    normalized === 'ERROR' ||
    normalized === 'AUTO_PAUSED'
  ) {
    return 'scheduler-panel__status scheduler-panel__status--failed';
  }
  if (normalized === 'RUNNING' || normalized === 'QUEUED') {
    return 'scheduler-panel__status scheduler-panel__status--running';
  }
  return 'scheduler-panel__status';
}

function executionLabel(task: ScheduledTask): string {
  if (task.sourceType === 'FILE' && task.executionMode === 'COMMAND') {
    return 'SCRIPT';
  }
  return task.executionMode;
}

function scheduleLabel(task: ScheduledTask): string {
  if (task.scheduleMode === 'LOOP') {
    return `Every ${task.intervalSeconds ?? '?'}s`;
  }
  return task.cronExpression || 'Missing cron';
}

function scheduleHint(task: ScheduledTask): string {
  return task.scheduleMode === 'LOOP' ? 'Loop queue runner' : 'Cron schedule';
}

function taskPreview(task: ScheduledTask): string {
  if (task.sourceType === 'FILE') {
    return task.executionMode === 'REQUEST'
      ? 'Body is loaded from the .task file only when this run is triggered.'
      : 'Shell script body is loaded from the .task file only when this run is triggered.';
  }

  if (task.executionMode === 'REQUEST') {
    return truncate(task.requestContent, 160) ?? 'Request task';
  }

  return truncate(task.command, 160) ?? 'Command task';
}

export function SchedulerPanel({
  onClose,
  className,
  showHeader = true,
}: SchedulerPanelProps) {
  const [tasks, setTasks] = useState<ScheduledTask[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [viewMode, setViewMode] = useState<ViewMode>('list');
  const [formData, setFormData] = useState<FormState>(createEmptyForm);
  const [editingTaskId, setEditingTaskId] = useState<string | null>(null);
  const [selectedTaskId, setSelectedTaskId] = useState<string | null>(null);
  const [logs, setLogs] = useState<TaskRunLog[]>([]);
  const [logsLoading, setLogsLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [activeAction, setActiveAction] = useState<string | null>(null);

  const selectedTask = useMemo(
    () => tasks.find((task) => task.id === selectedTaskId) ?? null,
    [tasks, selectedTaskId]
  );

  const fetchTasks = useCallback(async (silent = false) => {
    if (!silent) {
      setLoading(true);
    }

    try {
      const response = await managementApi.getScheduledTasks();
      setTasks(sortTasks(response));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to fetch scheduled tasks.');
    } finally {
      if (!silent) {
        setLoading(false);
      }
    }
  }, []);

  const fetchLogs = useCallback(async (taskId: string, silent = false) => {
    if (!silent) {
      setLogsLoading(true);
    }

    try {
      const response = await managementApi.getTaskRunLogs(taskId, 12);
      setLogs(response);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to fetch task logs.');
    } finally {
      if (!silent) {
        setLogsLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    void fetchTasks();
  }, [fetchTasks]);

  useEffect(() => {
    const timer = window.setInterval(() => {
      void fetchTasks(true);
      if (selectedTaskId) {
        void fetchLogs(selectedTaskId, true);
      }
    }, POLL_INTERVAL_MS);

    return () => {
      window.clearInterval(timer);
    };
  }, [fetchLogs, fetchTasks, selectedTaskId]);

  useEffect(() => {
    if (!selectedTaskId) {
      setLogs([]);
      return;
    }
    void fetchLogs(selectedTaskId);
  }, [fetchLogs, selectedTaskId]);

  useEffect(() => {
    if (!selectedTaskId) {
      return;
    }
    const exists = tasks.some((task) => task.id === selectedTaskId);
    if (!exists) {
      setSelectedTaskId(null);
      setLogs([]);
    }
  }, [selectedTaskId, tasks]);

  const resetForm = () => {
    setFormData(createEmptyForm());
    setEditingTaskId(null);
    setViewMode('list');
  };

  const handleRefresh = async () => {
    await fetchTasks();
    if (selectedTaskId) {
      await fetchLogs(selectedTaskId);
    }
  };

  const handleCreateClick = () => {
    setFormData(createEmptyForm());
    setEditingTaskId(null);
    setViewMode('create');
    setError(null);
  };

  const handleEditClick = (task: ScheduledTask) => {
    if (task.sourceType === 'FILE') {
      setError('Task files are managed in .task/*.task.md and cannot be edited here.');
      return;
    }

    setFormData(toFormState(task));
    setEditingTaskId(task.id);
    setViewMode('edit');
    setError(null);
  };

  const handleSave = async () => {
    const validationError = validateForm(formData);
    if (validationError) {
      setError(validationError);
      return;
    }

    setSaving(true);
    try {
      const payload = toRequestPayload(formData, {
        forUpdate: viewMode === 'edit',
      });
      if (viewMode === 'edit' && editingTaskId) {
        await managementApi.updateScheduledTask(editingTaskId, payload);
      } else {
        await managementApi.createScheduledTask(payload as CreateTaskRequest);
      }

      await fetchTasks();
      resetForm();
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save task.');
    } finally {
      setSaving(false);
    }
  };

  const runTaskAction = async (actionKey: string, action: () => Promise<void>) => {
    setActiveAction(actionKey);
    try {
      await action();
      await fetchTasks(true);
      if (selectedTaskId) {
        await fetchLogs(selectedTaskId, true);
      }
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Task action failed.');
    } finally {
      setActiveAction(null);
    }
  };

  const handleRunNow = async (task: ScheduledTask) => {
    await runTaskAction(`${task.id}:run`, async () => {
      await managementApi.runScheduledTaskNow(task.id);
    });
  };

  const handleToggleEnabled = async (task: ScheduledTask) => {
    await runTaskAction(`${task.id}:toggle`, async () => {
      if (task.enabled) {
        await managementApi.pauseScheduledTask(task.id);
      } else {
        await managementApi.resumeScheduledTask(task.id);
      }
    });
  };

  const handleDelete = async (task: ScheduledTask) => {
    if (task.sourceType === 'FILE') {
      setError('Task files are deleted by removing the corresponding .task file.');
      return;
    }

    if (!window.confirm(`Delete task "${task.name}"?`)) {
      return;
    }

    await runTaskAction(`${task.id}:delete`, async () => {
      await managementApi.deleteScheduledTask(task.id);
      if (selectedTaskId === task.id) {
        setSelectedTaskId(null);
        setLogs([]);
      }
    });
  };

  const rootClassName = ['scheduler-panel', className].filter(Boolean).join(' ');

  const renderToolbar = () => (
    <div className="scheduler-panel__toolbar">
      <button
        className="scheduler-panel__btn scheduler-panel__btn--primary"
        onClick={handleCreateClick}
      >
        + Manual Task
      </button>
      <button className="scheduler-panel__btn" onClick={() => void handleRefresh()} disabled={loading}>
        Refresh
      </button>
    </div>
  );

  const renderList = () => (
    <>
      <div className="scheduler-panel__intro">
        <div className="scheduler-panel__intro-copy">
          <div className="scheduler-panel__intro-label">Task Files</div>
          <h3>Progressive task loading</h3>
          <p>
            Files in <code>.task/*.task.md</code> are indexed by front-matter at startup. The
            body is loaded only when the run actually fires, so prompt/script content stays
            hidden until execution time.
          </p>
          <p>
            Scheduler runs through a single FIFO queue. If two jobs trigger together, the later
            one waits automatically instead of racing.
          </p>
        </div>
        <pre className="scheduler-panel__intro-code">
          <code>{TASK_FILE_SAMPLE}</code>
        </pre>
      </div>

      {renderToolbar()}

      <div className="scheduler-panel__list">
        {loading ? (
          <div className="scheduler-panel__loading">Loading scheduler tasks...</div>
        ) : tasks.length === 0 ? (
          <div className="scheduler-panel__empty">
            No tasks yet. Create a manual task or drop a <code>*.task.md</code> file into{' '}
            <code>.task/</code>.
          </div>
        ) : (
          tasks.map((task) => {
            const sourceLabel = task.sourceType === 'FILE' ? 'TASK FILE' : 'MANUAL';
            const execution = executionLabel(task);
            const preview = taskPreview(task);
            const isActionBusy = activeAction?.startsWith(`${task.id}:`) ?? false;

            return (
              <div
                key={task.id}
                className={`scheduler-panel__item ${!task.enabled ? 'scheduler-panel__item--disabled' : ''}`}
              >
                <div className="scheduler-panel__item-header">
                  <span className="scheduler-panel__item-name">{task.name}</span>
                  <span className="scheduler-panel__tag">{sourceLabel}</span>
                  <span className="scheduler-panel__tag scheduler-panel__tag--muted">
                    {task.scheduleMode}
                  </span>
                  <span className="scheduler-panel__tag scheduler-panel__tag--muted">
                    {execution}
                  </span>
                  <span
                    className={`scheduler-panel__item-status ${task.enabled ? 'scheduler-panel__item-status--enabled' : ''}`}
                  >
                    {task.autoPaused ? 'AUTO-PAUSED' : task.enabled ? 'ON' : 'OFF'}
                  </span>
                </div>

                {task.description && (
                  <div className="scheduler-panel__item-desc">{task.description}</div>
                )}

                <div className="scheduler-panel__item-cron">
                  <code>{scheduleLabel(task)}</code>
                </div>
                <div className="scheduler-panel__item-hint">{scheduleHint(task)}</div>

                <div className="scheduler-panel__item-command">
                  <code>{preview}</code>
                </div>

                <div className="scheduler-panel__item-meta">
                  <div>
                    <span className="scheduler-panel__meta-label">Source</span>
                    <span>
                      {task.sourceType === 'FILE'
                        ? task.sourcePath ?? 'Missing source path'
                        : 'Managed from UI/API'}
                    </span>
                  </div>
                  <div>
                    <span className="scheduler-panel__meta-label">Next Run</span>
                    <span>{formatDateTime(task.nextRunAt)}</span>
                  </div>
                  <div>
                    <span className="scheduler-panel__meta-label">Last Run</span>
                    <span>{formatDateTime(task.lastRunAt)}</span>
                    <span className={statusTone(task.lastRunStatus)}>
                      {task.lastRunStatus ?? 'IDLE'}
                    </span>
                  </div>
                  <div>
                    <span className="scheduler-panel__meta-label">Penalty</span>
                    <span>{task.penaltyPoints} / 3</span>
                    {task.requestUseOrchestrator && (
                      <span className="scheduler-panel__tag scheduler-panel__tag--muted">
                        Orchestrator
                      </span>
                    )}
                  </div>
                  {task.lastRunResult && (
                    <div>
                      <span className="scheduler-panel__meta-label">Last Result</span>
                      <span>{truncate(task.lastRunResult, 180)}</span>
                    </div>
                  )}
                </div>

                <div className="scheduler-panel__item-actions">
                  <button
                    className="scheduler-panel__btn scheduler-panel__btn--small scheduler-panel__btn--execute"
                    onClick={() => void handleRunNow(task)}
                    disabled={isActionBusy}
                  >
                    {activeAction === `${task.id}:run` ? 'Queueing...' : 'Queue Run'}
                  </button>
                  <button
                    className="scheduler-panel__btn scheduler-panel__btn--small"
                    onClick={() => void handleToggleEnabled(task)}
                    disabled={isActionBusy}
                  >
                    {activeAction === `${task.id}:toggle`
                      ? 'Saving...'
                      : task.enabled
                        ? 'Pause'
                        : 'Resume'}
                  </button>
                  <button
                    className="scheduler-panel__btn scheduler-panel__btn--small"
                    onClick={() => setSelectedTaskId(task.id)}
                    disabled={isActionBusy}
                  >
                    {selectedTaskId === task.id ? 'Viewing Logs' : 'Logs'}
                  </button>
                  {task.sourceType === 'MANUAL' && (
                    <>
                      <button
                        className="scheduler-panel__btn scheduler-panel__btn--small"
                        onClick={() => handleEditClick(task)}
                        disabled={isActionBusy}
                      >
                        Edit
                      </button>
                      <button
                        className="scheduler-panel__btn scheduler-panel__btn--small scheduler-panel__btn--danger"
                        onClick={() => void handleDelete(task)}
                        disabled={isActionBusy}
                      >
                        Delete
                      </button>
                    </>
                  )}
                </div>
              </div>
            );
          })
        )}
      </div>

      {selectedTask && (
        <div className="scheduler-panel__logs">
          <div className="scheduler-panel__logs-header">
            <h3>{selectedTask.name} Logs</h3>
            <button
              className="scheduler-panel__btn scheduler-panel__btn--small"
              onClick={() => setSelectedTaskId(null)}
            >
              Close
            </button>
          </div>
          <div className="scheduler-panel__logs-summary">
            <span>{selectedTask.sourceType === 'FILE' ? 'Task file' : 'Manual task'}</span>
            <span>{scheduleLabel(selectedTask)}</span>
            <span>Penalty {selectedTask.penaltyPoints} / 3</span>
          </div>
          {logsLoading ? (
            <div className="scheduler-panel__loading">Loading logs...</div>
          ) : logs.length === 0 ? (
            <div className="scheduler-panel__empty">No run history yet.</div>
          ) : (
            <div className="scheduler-panel__logs-list">
              {logs.map((log) => (
                <div key={log.id} className="scheduler-panel__log-item">
                  <div className="scheduler-panel__log-header">
                    <span className={statusTone(log.status)}>{log.status}</span>
                    <span className="scheduler-panel__log-time">{formatDateTime(log.startTime)}</span>
                    <span className="scheduler-panel__log-duration">{log.durationMs} ms</span>
                  </div>
                  {log.result && (
                    <pre className="scheduler-panel__log-output">{log.result}</pre>
                  )}
                  {log.error && (
                    <pre className="scheduler-panel__log-error">{log.error}</pre>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </>
  );

  const renderPresetButtons = () => {
    if (formData.scheduleMode === 'CRON') {
      return (
        <div className="scheduler-panel__preset-btns">
          <button type="button" onClick={() => setFormData((prev) => ({ ...prev, cronExpression: '0 9 * * 1-5' }))}>
            Weekdays 09:00
          </button>
          <button type="button" onClick={() => setFormData((prev) => ({ ...prev, cronExpression: '0 0 * * * *' }))}>
            Hourly
          </button>
          <button type="button" onClick={() => setFormData((prev) => ({ ...prev, cronExpression: '0 30 9 * * *' }))}>
            Daily 09:30
          </button>
        </div>
      );
    }

    return (
      <div className="scheduler-panel__preset-btns">
        <button type="button" onClick={() => setFormData((prev) => ({ ...prev, intervalSeconds: '60' }))}>
          60s
        </button>
        <button type="button" onClick={() => setFormData((prev) => ({ ...prev, intervalSeconds: '300' }))}>
          5 min
        </button>
        <button type="button" onClick={() => setFormData((prev) => ({ ...prev, intervalSeconds: '1800' }))}>
          30 min
        </button>
      </div>
    );
  };

  const renderForm = () => {
    const isEditing = viewMode === 'edit';

    return (
      <div className="scheduler-panel__form">
        <div className="scheduler-panel__form-banner">
          <div>
            <div className="scheduler-panel__intro-label">Manual Task</div>
            <h3>{isEditing ? 'Edit scheduled task' : 'Create scheduled task'}</h3>
            <p>
              Manual tasks live in the database and are editable here. If you want the
              progressive-loading experience, create a file in <code>.task/</code> instead.
            </p>
          </div>
        </div>

        <div className="scheduler-panel__form-row">
          <label>Name *</label>
          <input
            type="text"
            value={formData.name}
            onChange={(event) => setFormData((prev) => ({ ...prev, name: event.target.value }))}
            placeholder="daily-brief"
          />
        </div>

        <div className="scheduler-panel__form-row">
          <label>Description</label>
          <input
            type="text"
            value={formData.description}
            onChange={(event) => setFormData((prev) => ({ ...prev, description: event.target.value }))}
            placeholder="Optional operator note"
          />
        </div>

        <div className="scheduler-panel__form-row">
          <label>Schedule Mode</label>
          <select
            value={formData.scheduleMode}
            onChange={(event) =>
              setFormData((prev) => ({
                ...prev,
                scheduleMode: event.target.value as 'CRON' | 'LOOP',
              }))
            }
          >
            <option value="CRON">CRON</option>
            <option value="LOOP">LOOP</option>
          </select>
        </div>

        <div className="scheduler-panel__cron-presets">
          <div className="scheduler-panel__form-row">
            <label>{formData.scheduleMode === 'CRON' ? 'Cron Expression *' : 'Interval Seconds *'}</label>
            {formData.scheduleMode === 'CRON' ? (
              <input
                type="text"
                value={formData.cronExpression}
                onChange={(event) =>
                  setFormData((prev) => ({ ...prev, cronExpression: event.target.value }))
                }
                placeholder="0 9 * * 1-5"
              />
            ) : (
              <input
                type="number"
                min="1"
                step="1"
                value={formData.intervalSeconds}
                onChange={(event) =>
                  setFormData((prev) => ({ ...prev, intervalSeconds: event.target.value }))
                }
                placeholder="300"
              />
            )}
            <div className="scheduler-panel__form-hint">
              {formData.scheduleMode === 'CRON'
                ? 'Uses Spring 6-field cron syntax.'
                : 'LOOP mode queues one run every N seconds.'}
            </div>
          </div>
          {renderPresetButtons()}
        </div>

        <div className="scheduler-panel__form-row">
          <label>Execution Mode</label>
          <select
            value={formData.executionMode}
            onChange={(event) =>
              setFormData((prev) => ({
                ...prev,
                executionMode: event.target.value as 'COMMAND' | 'REQUEST',
              }))
            }
          >
            <option value="REQUEST">REQUEST</option>
            <option value="COMMAND">COMMAND</option>
          </select>
          <div className="scheduler-panel__form-hint">
            REQUEST simulates a user message. COMMAND supports plain shell text or prefixes like
            <code> agent:</code>, <code> shell:</code>, <code> request:</code>.
          </div>
        </div>

        {formData.executionMode === 'REQUEST' ? (
          <>
            <div className="scheduler-panel__form-row">
              <label>Request Content *</label>
              <textarea
                value={formData.requestContent}
                onChange={(event) =>
                  setFormData((prev) => ({ ...prev, requestContent: event.target.value }))
                }
                placeholder="Ask Lavis to generate a morning brief..."
              />
            </div>
            <label className="scheduler-panel__checkbox-row">
              <input
                type="checkbox"
                checked={formData.requestUseOrchestrator}
                onChange={(event) =>
                  setFormData((prev) => ({
                    ...prev,
                    requestUseOrchestrator: event.target.checked,
                  }))
                }
              />
              Run through orchestrator
            </label>
          </>
        ) : (
          <div className="scheduler-panel__form-row">
            <label>Command *</label>
            <textarea
              value={formData.command}
              onChange={(event) =>
                setFormData((prev) => ({ ...prev, command: event.target.value }))
              }
              placeholder={'shell: echo "health check"'}
            />
          </div>
        )}

        <label className="scheduler-panel__checkbox-row">
          <input
            type="checkbox"
            checked={formData.enabled}
            onChange={(event) =>
              setFormData((prev) => ({ ...prev, enabled: event.target.checked }))
            }
          />
          Enable immediately after saving
        </label>

        <div className="scheduler-panel__form-actions">
          <button className="scheduler-panel__btn" onClick={resetForm} disabled={saving}>
            Cancel
          </button>
          <button
            className="scheduler-panel__btn scheduler-panel__btn--primary"
            onClick={() => void handleSave()}
            disabled={saving}
          >
            {saving ? 'Saving...' : isEditing ? 'Save Changes' : 'Create Task'}
          </button>
        </div>
      </div>
    );
  };

  return (
    <div className={rootClassName}>
      {showHeader && (
        <div className="scheduler-panel__header">
          <h2>Scheduler</h2>
          {onClose && (
            <button
              className="scheduler-panel__close"
              onClick={onClose}
              aria-label="Close Scheduler"
            >
              x
            </button>
          )}
        </div>
      )}

      {error && (
        <div className="scheduler-panel__error">
          <span>{error}</span>
          <button onClick={() => setError(null)} aria-label="Clear scheduler error">
            x
          </button>
        </div>
      )}

      <div className="scheduler-panel__content">
        {viewMode === 'list' ? renderList() : renderForm()}
      </div>
    </div>
  );
}
