CREATE TABLE receipts (
 id UUID PRIMARY KEY,
 user_id UUID NOT NULL REFERENCES users(id),
 merchant VARCHAR(200) NOT NULL CHECK (length(btrim(merchant)) > 0),
 purchase_date DATE NOT NULL,
 amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
 currency VARCHAR(3) NOT NULL DEFAULT 'USD' CHECK (currency = 'USD'),
 category VARCHAR(30),
 description VARCHAR(2000),
 original_file_name VARCHAR(255) NOT NULL,
 stored_file_name VARCHAR(255) NOT NULL UNIQUE,
 content_type VARCHAR(100) NOT NULL,
 file_size BIGINT NOT NULL CHECK (file_size > 0 AND file_size <= 10485760),
 tax_relevant BOOLEAN NOT NULL DEFAULT FALSE,
 notes VARCHAR(4000),
 status VARCHAR(30) NOT NULL DEFAULT 'SAVED' CHECK (status IN ('SAVED','DELETION_PENDING')),
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 CONSTRAINT receipt_category CHECK (category IS NULL OR category IN ('BUSINESS','MEDICAL','CHARITY','HOME','EDUCATION','TRANSPORTATION','MEALS','OFFICE','OTHER'))
);
CREATE INDEX idx_receipts_owner_date ON receipts(user_id,purchase_date DESC,id);
CREATE INDEX idx_receipts_owner_category_date ON receipts(user_id,category,purchase_date);
