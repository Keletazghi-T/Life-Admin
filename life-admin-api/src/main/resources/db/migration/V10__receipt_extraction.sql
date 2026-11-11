-- Add suggestions without changing any existing confirmed fields or legacy sample metadata.
ALTER TABLE receipts ADD COLUMN suggested_merchant VARCHAR(200);
ALTER TABLE receipts ADD COLUMN suggested_purchase_date DATE;
ALTER TABLE receipts ADD COLUMN suggested_amount NUMERIC(14,2) CHECK (suggested_amount IS NULL OR suggested_amount > 0);
ALTER TABLE receipts ADD COLUMN suggested_currency VARCHAR(3);
ALTER TABLE receipts ADD COLUMN suggested_category VARCHAR(30);
ALTER TABLE receipts ADD COLUMN extraction_status VARCHAR(40) NOT NULL DEFAULT 'NOT_ATTEMPTED'
 CHECK (extraction_status IN ('NOT_ATTEMPTED','EXTRACTED','PARTIAL','NO_MATCH','OCR_REQUIRED','EXTRACTION_UNAVAILABLE'));
ALTER TABLE receipts ADD COLUMN extraction_method VARCHAR(40);
ALTER TABLE receipts ADD COLUMN extraction_timestamp TIMESTAMPTZ;
ALTER TABLE receipts ADD COLUMN extraction_error VARCHAR(1000);
