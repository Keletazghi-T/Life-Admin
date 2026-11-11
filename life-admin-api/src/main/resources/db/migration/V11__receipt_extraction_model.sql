-- Preserve all prior suggestions and confirmed values; record the configured image model.
ALTER TABLE receipts ADD COLUMN extraction_model VARCHAR(100);
