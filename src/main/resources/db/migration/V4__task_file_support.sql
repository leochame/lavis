-- Task file support:
-- 1) file-backed scheduled tasks (.task/*.task.md)
-- 2) runtime progressive loading source markers
-- 3) fixed penalty points with auto pause

ALTER TABLE scheduled_tasks ADD COLUMN source_type TEXT DEFAULT 'MANUAL';
ALTER TABLE scheduled_tasks ADD COLUMN source_path TEXT;
ALTER TABLE scheduled_tasks ADD COLUMN penalty_points INTEGER DEFAULT 0;
ALTER TABLE scheduled_tasks ADD COLUMN auto_paused INTEGER DEFAULT 0;

UPDATE scheduled_tasks
SET source_type = 'MANUAL'
WHERE source_type IS NULL OR TRIM(source_type) = '';

UPDATE scheduled_tasks
SET penalty_points = 0
WHERE penalty_points IS NULL;

UPDATE scheduled_tasks
SET auto_paused = 0
WHERE auto_paused IS NULL;
