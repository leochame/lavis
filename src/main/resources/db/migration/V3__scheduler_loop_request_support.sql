-- Scheduler enhancements:
-- 1) LOOP schedule mode (fixed interval)
-- 2) REQUEST execution mode (simulate user text requests)
-- 3) next_run_at persistence for UI display

ALTER TABLE scheduled_tasks ADD COLUMN next_run_at TEXT;
ALTER TABLE scheduled_tasks ADD COLUMN schedule_mode TEXT DEFAULT 'CRON';
ALTER TABLE scheduled_tasks ADD COLUMN interval_seconds INTEGER;
ALTER TABLE scheduled_tasks ADD COLUMN execution_mode TEXT DEFAULT 'COMMAND';
ALTER TABLE scheduled_tasks ADD COLUMN request_content TEXT;
ALTER TABLE scheduled_tasks ADD COLUMN request_use_orchestrator INTEGER DEFAULT 0;

UPDATE scheduled_tasks
SET schedule_mode = 'CRON'
WHERE schedule_mode IS NULL OR TRIM(schedule_mode) = '';

UPDATE scheduled_tasks
SET execution_mode = 'COMMAND'
WHERE execution_mode IS NULL OR TRIM(execution_mode) = '';

UPDATE scheduled_tasks
SET request_use_orchestrator = 0
WHERE request_use_orchestrator IS NULL;
