-- Additive, account-scoped deduplication for upload submissions. Historical rows remain unchanged.
ALTER TABLE receipts ADD COLUMN upload_request_id UUID;
CREATE UNIQUE INDEX receipts_owner_upload_request ON receipts(user_id, upload_request_id) WHERE upload_request_id IS NOT NULL;
