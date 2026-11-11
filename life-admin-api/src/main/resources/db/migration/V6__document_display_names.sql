ALTER TABLE documents ADD COLUMN display_name VARCHAR(255);
UPDATE documents SET display_name=original_file_name;
ALTER TABLE documents ALTER COLUMN display_name SET NOT NULL;
ALTER TABLE documents ADD CONSTRAINT document_display_name_nonblank CHECK (length(btrim(display_name)) > 0);
CREATE INDEX idx_documents_user_name ON documents(user_id,lower(display_name),id);
