-- Add a separate nullable suggestion; never backfill or overwrite confirmed descriptions.
ALTER TABLE receipts ADD COLUMN suggested_description VARCHAR(240);
