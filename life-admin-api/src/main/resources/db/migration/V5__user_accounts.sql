-- Anonymous workspace IDs cannot establish verified account ownership.
-- Refuse to migrate such data; use a fresh development DB as documented in README.
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM documents) THEN
  RAISE EXCEPTION 'Legacy anonymous documents require an explicit migration plan. Use a fresh development database; do not assign workspace IDs to accounts.';
 END IF;
END $$;
CREATE TABLE users (
 id UUID PRIMARY KEY,
 email VARCHAR(254) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 CONSTRAINT normalized_email CHECK (email = lower(btrim(email)))
);
ALTER TABLE documents ADD CONSTRAINT documents_user_fk FOREIGN KEY (user_id) REFERENCES users(id);
