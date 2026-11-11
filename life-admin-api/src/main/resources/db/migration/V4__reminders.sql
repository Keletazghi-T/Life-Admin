CREATE TABLE reminders (
 id UUID PRIMARY KEY,
 task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
 reminder_date DATE NOT NULL,
 status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','DISMISSED')),
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 CONSTRAINT reminders_task_date_unique UNIQUE(task_id,reminder_date)
);
CREATE INDEX tasks_due_todo_idx ON tasks(due_date,id) WHERE status='TODO' AND due_date IS NOT NULL;
