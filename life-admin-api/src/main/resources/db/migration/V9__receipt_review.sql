-- Preserve manually entered legacy receipts as reviewed; drafts can omit purchase details.
ALTER TABLE receipts ADD COLUMN review_status VARCHAR(30) NOT NULL DEFAULT 'REVIEWED'
 CHECK (review_status IN ('UPLOADED','NEEDS_REVIEW','REVIEWED'));
ALTER TABLE receipts ADD COLUMN extraction_source VARCHAR(40);
ALTER TABLE receipts ADD COLUMN extracted_at TIMESTAMPTZ;
ALTER TABLE receipts ADD COLUMN extraction_details TEXT;
ALTER TABLE receipts ALTER COLUMN merchant DROP NOT NULL;
ALTER TABLE receipts ALTER COLUMN purchase_date DROP NOT NULL;
ALTER TABLE receipts ALTER COLUMN amount DROP NOT NULL;
ALTER TABLE receipts ADD CONSTRAINT reviewed_receipt_details CHECK
 (review_status <> 'REVIEWED' OR (merchant IS NOT NULL AND length(btrim(merchant)) > 0 AND purchase_date IS NOT NULL AND amount IS NOT NULL AND amount > 0));
