import { useState, useEffect, useCallback } from 'react';
import { managementApi, type Skill, type CreateSkillRequest } from '../api/managementApi';
import './SkillsPanel.css';

interface SkillsPanelProps {
  onClose?: () => void;
}

type ViewMode = 'list' | 'create' | 'edit' | 'detail';

export function SkillsPanel({ onClose }: SkillsPanelProps) {
  const [skills, setSkills] = useState<Skill[]>([]);
  const [categories, setCategories] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [viewMode, setViewMode] = useState<ViewMode>('list');
  const [selectedSkill, setSelectedSkill] = useState<Skill | null>(null);
  const [filterCategory, setFilterCategory] = useState<string>('');
  const [executing, setExecuting] = useState<string | null>(null);
  const [executeResult, setExecuteResult] = useState<{ success: boolean; output: string } | null>(null);

  // Form state
  const [formData, setFormData] = useState<CreateSkillRequest>({
    name: '',
    description: '',
    category: '',
    version: '1.0.0',
    author: '',
    content: '',
    command: '',
  });

  const fetchSkills = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [skillsData, categoriesData] = await Promise.all([
        managementApi.getSkills(undefined, filterCategory || undefined),
        managementApi.getSkillCategories(),
      ]);
      setSkills(skillsData);
      setCategories(categoriesData);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to fetch skills');
    } finally {
      setLoading(false);
    }
  }, [filterCategory]);

  useEffect(() => {
    fetchSkills();
  }, [fetchSkills]);

  const handleReload = async () => {
    setLoading(true);
    try {
      const result = await managementApi.reloadSkills();
      await fetchSkills();
      setError(null);
      alert(`Reloaded ${result.count} skills from filesystem`);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to reload skills');
    } finally {
      setLoading(false);
    }
  };

  const handleCreate = async () => {
    if (!formData.name || !formData.command) {
      setError('Name and command are required');
      return;
    }
    setLoading(true);
    try {
      await managementApi.createSkill(formData);
      setViewMode('list');
      setFormData({ name: '', description: '', category: '', version: '1.0.0', author: '', content: '', command: '' });
      await fetchSkills();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create skill');
    } finally {
      setLoading(false);
    }
  };

  const handleUpdate = async () => {
    if (!selectedSkill) return;
    setLoading(true);
    try {
      await managementApi.updateSkill(selectedSkill.id, formData);
      setViewMode('list');
      setSelectedSkill(null);
      await fetchSkills();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to update skill');
    } finally {
      setLoading(false);
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm('Are you sure you want to delete this skill?')) return;
    setLoading(true);
    try {
      await managementApi.deleteSkill(id);
      await fetchSkills();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete skill');
    } finally {
      setLoading(false);
    }
  };

  const handleToggleEnabled = async (skill: Skill) => {
    try {
      await managementApi.updateSkill(skill.id, { enabled: !skill.enabled });
      await fetchSkills();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to toggle skill');
    }
  };

  const handleExecute = async (skill: Skill) => {
    setExecuting(skill.id);
    setExecuteResult(null);
    try {
      const result = await managementApi.executeSkill(skill.id);
      setExecuteResult({
        success: result.success,
        output: result.success ? (result.output || 'Success') : (result.error || 'Failed'),
      });
    } catch (err) {
      setExecuteResult({
        success: false,
        output: err instanceof Error ? err.message : 'Execution failed',
      });
    } finally {
      setExecuting(null);
    }
  };

  const openEdit = (skill: Skill) => {
    setSelectedSkill(skill);
    setFormData({
      name: skill.name,
      description: skill.description || '',
      category: skill.category || '',
      version: skill.version || '1.0.0',
      author: skill.author || '',
      content: skill.content,
      command: skill.command,
    });
    setViewMode('edit');
  };

  const openDetail = (skill: Skill) => {
    setSelectedSkill(skill);
    setViewMode('detail');
  };

  const renderList = () => (
    <>
      <div className="skills-panel__toolbar">
        <select
          value={filterCategory}
          onChange={(e) => setFilterCategory(e.target.value)}
          className="skills-panel__filter"
        >
          <option value="">All Categories</option>
          {categories.map((cat) => (
            <option key={cat} value={cat}>{cat}</option>
          ))}
        </select>
        <button className="skills-panel__btn skills-panel__btn--primary" onClick={() => setViewMode('create')}>
          + New Skill
        </button>
        <button className="skills-panel__btn" onClick={handleReload} disabled={loading}>
          Reload
        </button>
      </div>

      {executeResult && (
        <div className={`skills-panel__result ${executeResult.success ? 'skills-panel__result--success' : 'skills-panel__result--error'}`}>
          <span>{executeResult.output}</span>
          <button onClick={() => setExecuteResult(null)}>x</button>
        </div>
      )}

      <div className="skills-panel__list">
        {skills.length === 0 ? (
          <div className="skills-panel__empty">No skills found</div>
        ) : (
          skills.map((skill) => (
            <div key={skill.id} className={`skills-panel__item ${!skill.enabled ? 'skills-panel__item--disabled' : ''}`}>
              <div className="skills-panel__item-header">
                <span className="skills-panel__item-name" onClick={() => openDetail(skill)}>
                  {skill.name}
                </span>
                {skill.category && (
                  <span className="skills-panel__item-category">{skill.category}</span>
                )}
                <span className={`skills-panel__item-status ${skill.enabled ? 'skills-panel__item-status--enabled' : ''}`}>
                  {skill.enabled ? 'ON' : 'OFF'}
                </span>
              </div>
              {skill.description && (
                <div className="skills-panel__item-desc">{skill.description}</div>
              )}
              <div className="skills-panel__item-meta">
                <span>v{skill.version || '1.0.0'}</span>
                <span>Used: {skill.useCount}</span>
              </div>
              <div className="skills-panel__item-actions">
                <button
                  className="skills-panel__btn skills-panel__btn--small skills-panel__btn--execute"
                  onClick={() => handleExecute(skill)}
                  disabled={executing === skill.id || !skill.enabled}
                >
                  {executing === skill.id ? '...' : 'Run'}
                </button>
                <button
                  className="skills-panel__btn skills-panel__btn--small"
                  onClick={() => handleToggleEnabled(skill)}
                >
                  {skill.enabled ? 'Disable' : 'Enable'}
                </button>
                <button
                  className="skills-panel__btn skills-panel__btn--small"
                  onClick={() => openEdit(skill)}
                >
                  Edit
                </button>
                <button
                  className="skills-panel__btn skills-panel__btn--small skills-panel__btn--danger"
                  onClick={() => handleDelete(skill.id)}
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
    <div className="skills-panel__form">
      <div className="skills-panel__form-row">
        <label>Name *</label>
        <input
          type="text"
          value={formData.name}
          onChange={(e) => setFormData({ ...formData, name: e.target.value })}
          placeholder="skill-name"
        />
      </div>
      <div className="skills-panel__form-row">
        <label>Description</label>
        <input
          type="text"
          value={formData.description}
          onChange={(e) => setFormData({ ...formData, description: e.target.value })}
          placeholder="What does this skill do?"
        />
      </div>
      <div className="skills-panel__form-row skills-panel__form-row--half">
        <div>
          <label>Category</label>
          <input
            type="text"
            value={formData.category}
            onChange={(e) => setFormData({ ...formData, category: e.target.value })}
            placeholder="utility"
          />
        </div>
        <div>
          <label>Version</label>
          <input
            type="text"
            value={formData.version}
            onChange={(e) => setFormData({ ...formData, version: e.target.value })}
            placeholder="1.0.0"
          />
        </div>
      </div>
      <div className="skills-panel__form-row">
        <label>Author</label>
        <input
          type="text"
          value={formData.author}
          onChange={(e) => setFormData({ ...formData, author: e.target.value })}
          placeholder="your-name"
        />
      </div>
      <div className="skills-panel__form-row">
        <label>Command *</label>
        <input
          type="text"
          value={formData.command}
          onChange={(e) => setFormData({ ...formData, command: e.target.value })}
          placeholder="shell:echo hello or agent:do something"
        />
        <span className="skills-panel__form-hint">
          Prefix: shell: for shell commands, agent: for AI agent tasks
        </span>
      </div>
      <div className="skills-panel__form-row">
        <label>Content (Markdown)</label>
        <textarea
          value={formData.content}
          onChange={(e) => setFormData({ ...formData, content: e.target.value })}
          placeholder="# Skill Documentation..."
          rows={6}
        />
      </div>
      <div className="skills-panel__form-actions">
        <button
          className="skills-panel__btn"
          onClick={() => {
            setViewMode('list');
            setSelectedSkill(null);
            setFormData({ name: '', description: '', category: '', version: '1.0.0', author: '', content: '', command: '' });
          }}
        >
          Cancel
        </button>
        <button
          className="skills-panel__btn skills-panel__btn--primary"
          onClick={viewMode === 'create' ? handleCreate : handleUpdate}
          disabled={loading}
        >
          {viewMode === 'create' ? 'Create' : 'Save'}
        </button>
      </div>
    </div>
  );

  const renderDetail = () => {
    if (!selectedSkill) return null;
    return (
      <div className="skills-panel__detail">
        <div className="skills-panel__detail-header">
          <h3>{selectedSkill.name}</h3>
          <span className={`skills-panel__item-status ${selectedSkill.enabled ? 'skills-panel__item-status--enabled' : ''}`}>
            {selectedSkill.enabled ? 'ENABLED' : 'DISABLED'}
          </span>
        </div>
        {selectedSkill.description && (
          <p className="skills-panel__detail-desc">{selectedSkill.description}</p>
        )}
        <div className="skills-panel__detail-info">
          <div><strong>Category:</strong> {selectedSkill.category || '-'}</div>
          <div><strong>Version:</strong> {selectedSkill.version || '1.0.0'}</div>
          <div><strong>Author:</strong> {selectedSkill.author || '-'}</div>
          <div><strong>Source:</strong> {selectedSkill.installSource || '-'}</div>
          <div><strong>Use Count:</strong> {selectedSkill.useCount}</div>
          <div><strong>Last Used:</strong> {selectedSkill.lastUsedAt ? new Date(selectedSkill.lastUsedAt).toLocaleString() : 'Never'}</div>
        </div>
        <div className="skills-panel__detail-section">
          <h4>Command</h4>
          <code className="skills-panel__detail-code">{selectedSkill.command}</code>
        </div>
        {selectedSkill.content && (
          <div className="skills-panel__detail-section">
            <h4>Documentation</h4>
            <pre className="skills-panel__detail-content">{selectedSkill.content}</pre>
          </div>
        )}
        <div className="skills-panel__form-actions">
          <button className="skills-panel__btn" onClick={() => setViewMode('list')}>
            Back
          </button>
          <button className="skills-panel__btn" onClick={() => openEdit(selectedSkill)}>
            Edit
          </button>
          <button
            className="skills-panel__btn skills-panel__btn--primary"
            onClick={() => handleExecute(selectedSkill)}
            disabled={executing === selectedSkill.id || !selectedSkill.enabled}
          >
            {executing === selectedSkill.id ? 'Running...' : 'Execute'}
          </button>
        </div>
      </div>
    );
  };

  const getTitle = () => {
    switch (viewMode) {
      case 'create': return 'Create Skill';
      case 'edit': return 'Edit Skill';
      case 'detail': return 'Skill Details';
      default: return 'Skills';
    }
  };

  return (
    <div className="skills-panel">
      {onClose && (
        <div className="skills-panel__header">
          <h2>{getTitle()}</h2>
          <button className="skills-panel__close" onClick={onClose} aria-label="Close">×</button>
        </div>
      )}

      {error && (
        <div className="skills-panel__error">
          {error}
          <button onClick={() => setError(null)}>x</button>
        </div>
      )}

      <div className="skills-panel__content">
        {loading && viewMode === 'list' && skills.length === 0 ? (
          <div className="skills-panel__loading">Loading...</div>
        ) : viewMode === 'list' ? (
          renderList()
        ) : viewMode === 'detail' ? (
          renderDetail()
        ) : (
          renderForm()
        )}
      </div>
    </div>
  );
}
