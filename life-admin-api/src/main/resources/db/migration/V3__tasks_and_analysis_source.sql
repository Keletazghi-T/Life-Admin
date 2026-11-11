ALTER TABLE document_analyses ADD COLUMN source VARCHAR(30) NOT NULL DEFAULT 'AI'
    CHECK (source IN ('AI', 'DEVELOPMENT_MOCK'));
CREATE TABLE tasks (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    title VARCHAR(1000) NOT NULL CHECK (length(trim(title)) > 0),
    description VARCHAR(4000),
    due_date DATE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('TODO', 'COMPLETED')),
    development_mock BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_tasks_document_created ON tasks(document_id, created_at, id);
-- Retain generation history even when users edit or delete generated tasks.
CREATE TABLE task_generations (
    analysis_id UUID PRIMARY KEY REFERENCES document_analyses(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
