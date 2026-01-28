import { useState, useEffect, useCallback } from 'react';
import { managementApi, type ScheduledTask, type CreateTaskRequest, type TaskRunLog } from '../api/managementApi';
import './SchedulerPanel.css';

interface SchedulerPanelProps {
  onClose?: () => void;
}

type ViewMode = 'list' | 'create' | 'edit' | 'logs';

export function SchedulerPanel({ onClose }: SchedulerPanelProps) {
  const [tasks, setTasks] = useState<ScheduledTask[]>([]);
  const [logs, setLogs] = useState<TaskRunLog[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [viewMode, setViewMode] = useState<ViewMode>('list');
  const [selectedTask, setSelectedTask] = useState<ScheduledTask | null>(null);
  const [running, setRunning] = useState<string | null>(null);

  // Form state
  const [formData, setFormData] = useState<CreateTaskRequest>({
    name: '',
    description: '',
    cronExpression: '0 0 * * *',
    command: '',
  });

  const fetchTasks = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await managementApi.getScheduledTasks();
      setTasks(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to fetch tasks');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchTasks();
  }, [fetchTasks]);

  const fetchLogs = async (taskId: string) => {
    setLoading(true);
    try {
      const data = await managementApi.getTaskRunLogs(taskId, 20);
      setLogs(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to fetch logs');
    } finally {
      setLoading(false);
    }
  };

  const handleCreate = async () => {
    if (!formData.name || !formData.cronExpression || !formData.command) {
      setError('Name, cron expression, and command are required');
      return;
    }
    setLoading(true);
    try {
      await managementApi.createScheduledTask(formData);
      setViewMode('list');
      setFormData({ name: '', description: '', cronExpression: '0 0 * * *', command: '' });
      await fetchTasks();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create task');
    } finally {
      setLoading(false);
    }
  };

  const handleUpdate = async () => {
    if (!selectedTask) return;
    setLoading(true);
    try {
      await managementApi.updateScheduledTask(selectedTask.id, formData);
      setViewMode('list');
      setSelectedTask(null);
      await fetchTasks();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to update task');
    } finally {
      setLoading(false);
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm('Are you sure you want to delete this task?')) return;
    setLoading(true);
    try {
      await managementApi.deleteScheduledTask(id);
      await fetchTasks();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete task');
    } finally {
      setLoading(false);
    }
  };

  const handleToggleEnabled = async (task: ScheduledTask) => {
    try {
      if (task.enabled) {
        await managementApi.pauseScheduledTask(task.id);
      } else {
        await managementApi.resumeScheduledTask(task.id);
      }
      await fetchTasks();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to toggle task');
    }
  };

  const handleRunNow = async (task: ScheduledTask) => {
    setRunning(task.id);
    try {
      await managementApi.runScheduledTaskNow(task.id);
      await fetchTasks();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to run task');
    } finally {
      setRunning(null);
    }
  };

  const openEdit = (task: ScheduledTask) => {
    setSelectedTask(task);
    setFormData({
      name: task.name,
      description: task.description || '',
      cronExpression: task.cronExpression,
      command: task.command,
    });
    setViewMode('edit');
  };

  const openLogs = async (task: ScheduledTask) => {
    setSelectedTask(task);
    setViewMode('logs');
    await fetchLogs(task.id);
  };

  const formatDate = (dateStr: string | null) => {
    if (!dateStr) return '-';
    return new Date(dateStr).toLocaleString();
  };

  const getStatusColor = (status: string | null) => {
    switch (status) {
      case 'SUCCESS': return 'scheduler-panel__status--success';
      case 'FAILED': return 'scheduler-panel__status--failed';
      case 'RUNNING': return 'scheduler-panel__status--running';
      default: return '';
    }
  };

  const renderList = () => (
    <>
      <div className="scheduler-panel__toolbar">
        <button
          className="scheduler-panel__btn scheduler-panel__btn--primary"
          onClick={() => setViewMode('create')}
        >
          + New Task
        </button>
        <button className="scheduler-panel__btn" onClick={fetchTasks} disabled={loading}>
          Refresh
        </button>
      </div>

      <div className="scheduler-panel__list">
        {tasks.length === 0 ? (
          <div className="scheduler-panel__empty">No scheduled tasks</div>
        ) : (
          tasks.map((task) => (
            <div
              key={task.id}
              className={`scheduler-panel__item ${!task.enabled ? 'scheduler-panel__item--disabled' : ''}`}
            >
              <div className="scheduler-panel__item-header">
                <span className="scheduler-panel__item-name">{task.name}</span>
                <span className={`scheduler-panel__item-status ${task.enabled ? 'scheduler-panel__item-status--enabled' : ''}`}>
                  {task.enabled ? 'ACTIVE' : 'PAUSED'}
                </span>
              </div>
              {task.description && (
                <div className="scheduler-panel__item-desc">{task.description}</div>
              )}
              <div className="scheduler-panel__item-cron">
                <code>{task.cronExpression}</code>
              </div>
              <div className="scheduler-panel__item-command">
                <code>{task.command}</code>
              </div>
              <div className="scheduler-panel__item-meta">
                <div>
                  <span className="scheduler-panel__meta-label">Last Run:</span>
                  <span>{formatDate(task.lastRunAt)}</span>
                  {task.lastStatus && (
                    <span className={`scheduler-panel__status ${getStatusColor(task.lastStatus)}`}>
                      {task.lastStatus}
                    </span>
                  )}
                </div>
                <div>
                  <span className="scheduler-panel__meta-label">Next Run:</span>
                  <span>{formatDate(task.nextRunAt)}</span>
                </div>
                <div>
                  <span className="scheduler-panel__meta-label">Run Count:</span>
                  <span>{task.runCount}</span>
                </div>
              </div>
              <div className="scheduler-panel__item-actions">
                <button
                  className="scheduler-panel__btn scheduler-panel__btn--small scheduler-panel__btn--execute"
                  onClick={() => handleRunNow(task)}
                  disabled={running === task.id}
                >
                  {running === task.id ? '...' : 'Run Now'}
                </button>
                <button
                  className="scheduler-panel__btn scheduler-panel__btn--small"
                  onClick={() => handleToggleEnabled(task)}
                >
                  {task.enabled ? 'Pause' : 'Resume'}
                </button>
                <button
                  className="scheduler-panel__btn scheduler-panel__btn--small"
                  onClick={() => openLogs(task)}
                >
                  Logs
                </button>
                <button
                  className="scheduler-panel__btn scheduler-panel__btn--small"
                  onClick={() => openEdit(task)}
                >
                  Edit
                </button>
                <button
                  className="scheduler-panel__btn scheduler-panel__btn--small scheduler-panel__btn--danger"
                  onClick={() => handleDelete(task.id)}
                >
                  Delete
                </button>
              </div>
            </div>
          ))
        )}
      </div>
    </>
  );

  const renderForm = () => (
    <div className="scheduler-panel__form">
      <div className="scheduler-panel__form-row">
        <label>Name *</label>
        <input
          type="text"
          value={formData.name}
          onChange={(e) => setFormData({ ...formData, name: e.target.value })}
          placeholder="daily-backup"
        />
      </div>
      <div className="scheduler-panel__form-row">
        <label>Description</label>
        <input
          type="text"
          value={formData.description}
          onChange={(e) => setFormData({ ...formData, description: e.target.value })}
          placeholder="What does this task do?"
        />
      </div>
      <div className="scheduler-panel__form-row">
        <label>Cron Expression *</label>
        <input
          type="text"
          value={formData.cronExpression}
          onChange={(e) => setFormData({ ...formData, cronExpression: e.target.value })}
          placeholder="0 0 * * *"
        />
        <span className="scheduler-panel__form-hint">
          Format: minute hour day month weekday (e.g., "0 9 * * 1-5" = 9 AM weekdays)
        </span>
      </div>
      <div className="scheduler-panel__form-row">
        <label>Command *</label>
        <input
          type="text"
          value={formData.command}
          onChange={(e) => setFormData({ ...formData, command: e.target.value })}
          placeholder="shell:echo hello or agent:do something"
        />
        <span className="scheduler-panel__form-hint">
          Prefix: shell: for shell commands, agent: for AI agent tasks
        </span>
      </div>
      <div className="scheduler-panel__cron-presets">
        <span className="scheduler-panel__form-hint">Quick presets:</span>
        <div className="scheduler-panel__preset-btns">
          <button type="button" onClick={() => setFormData({ ...formData, cronExpression: '* * * * *' })}>
            Every minute
          </button>
          <button type="button" onClick={() => setFormData({ ...formData, cronExpression: '0 * * * *' })}>
            Every hour
          </button>
          <button type="button" onClick={() => setFormData({ ...formData, cronExpression: '0 0 * * *' })}>
            Daily midnight
          </button>
          <button type="button" onClick={() => setFormData({ ...formData, cronExpression: '0 9 * * 1-5' })}>
            Weekdays 9 AM
          </button>
          <button type="button" onClick={() => setFormData({ ...formData, cronExpression: '0 0 * * 0' })}>
            Weekly Sunday
          </button>
        </div>
      </div>
      <div className="scheduler-panel__form-actions">
        <button
          className="scheduler-panel__btn"
          onClick={() => {
            setViewMode('list');
            setSelectedTask(null);
            setFormData({ name: '', description: '', cronExpression: '0 0 * * *', command: '' });
          }}
        >
          Cancel
        </button>
        <button
          className="scheduler-panel__btn scheduler-panel__btn--primary"
          onClick={viewMode === 'create' ? handleCreate : handleUpdate}
          disabled={loading}
        >
          {viewMode === 'create' ? 'Create' : 'Save'}
        </button>
      </div>
    </div>
  );

  const renderLogs = () => (
    <div className="scheduler-panel__logs">
      <div className="scheduler-panel__logs-header">
        <h3>Run History: {selectedTask?.name}</h3>
        <button className="scheduler-panel__btn" onClick={() => setViewMode('list')}>
          Back
        </button>
      </div>
      {logs.length === 0 ? (
        <div className="scheduler-panel__empty">No run history</div>
      ) : (
        <div className="scheduler-panel__logs-list">
          {logs.map((log) => (
            <div key={log.id} className={`scheduler-panel__log-item ${getStatusColor(log.status)}`}>
              <div className="scheduler-panel__log-header">
                <span className={`scheduler-panel__status ${getStatusColor(log.status)}`}>
                  {log.status}
                </span>
                <span className="scheduler-panel__log-time">
                  {formatDate(log.startTime)}
                </span>
                <span className="scheduler-panel__log-duration">
                  {log.durationMs}ms
                </span>
              </div>
              {log.output && (
                <pre className="scheduler-panel__log-output">{log.output}</pre>
              )}
              {log.error && (
                <pre className="scheduler-panel__log-error">{log.error}</pre>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );

  const getTitle = () => {
    switch (viewMode) {
      case 'create': return 'Create Task';
      case 'edit': return 'Edit Task';
      case 'logs': return 'Task Logs';
      default: return 'Scheduler';
    }
  };

  return (
    <div className="scheduler-panel">
      {onClose && (
        <div className="scheduler-panel__header">
          <h2>{getTitle()}</h2>
          <button className="scheduler-panel__close" onClick={onClose} aria-label="Close">×</button>
        </div>
      )}

      {error && (
        <div className="scheduler-panel__error">
          {error}
          <button onClick={() => setError(null)}>x</button>
        </div>
      )}

      <div className="scheduler-panel__content">
        {loading && viewMode === 'list' && tasks.length === 0 ? (
          <div className="scheduler-panel__loading">Loading...</div>
        ) : viewMode === 'list' ? (
          renderList()
        ) : viewMode === 'logs' ? (
          renderLogs()
        ) : (
          renderForm()
        )}
      </div>
    </div>
  );
}
