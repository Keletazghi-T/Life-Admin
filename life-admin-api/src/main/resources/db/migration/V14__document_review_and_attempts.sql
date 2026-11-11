-- Additive review data: existing suggestions/tasks remain unchanged and unconfirmed.
ALTER TABLE document_analyses ADD COLUMN meaning TEXT;
ALTER TABLE document_analyses ADD COLUMN evidence JSONB NOT NULL DEFAULT '[]';
ALTER TABLE document_analyses ADD COLUMN important_dates JSONB NOT NULL DEFAULT '[]';
ALTER TABLE document_analyses ADD COLUMN confirmed_content JSONB;
ALTER TABLE document_analyses ADD COLUMN confirmed_at TIMESTAMPTZ;
ALTER TABLE document_analyses DROP CONSTRAINT document_analyses_source_check;
ALTER TABLE document_analyses ADD CONSTRAINT document_analyses_source_check CHECK (source IN ('AI','DEVELOPMENT_MOCK','MANUAL'));
CREATE TABLE document_analysis_attempts (
 document_id UUID PRIMARY KEY REFERENCES documents(id) ON DELETE CASCADE,
 attempt_id UUID NOT NULL,
 status VARCHAR(20) NOT NULL CHECK(status IN ('PROCESSING','SUCCEEDED','FAILED')),
 started_at TIMESTAMPTZ NOT NULL,
 finished_at TIMESTAMPTZ,
 message VARCHAR(500)
);
CREATE TABLE document_action_generations (
 analysis_id UUID NOT NULL REFERENCES document_analyses(id) ON DELETE CASCADE,
 action_key VARCHAR(64) NOT NULL,
 PRIMARY KEY(analysis_id,action_key)
);
