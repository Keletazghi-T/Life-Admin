-- Safe metadata only; existing attempts deliberately remain unmodified/unknown.
ALTER TABLE document_analysis_attempts ADD COLUMN diagnostics JSONB;
