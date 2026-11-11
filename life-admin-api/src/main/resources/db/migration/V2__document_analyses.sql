CREATE TABLE document_analyses (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL UNIQUE REFERENCES documents(id) ON DELETE CASCADE,
    document_type VARCHAR(200) NOT NULL,
    summary TEXT NOT NULL,
    action_required BOOLEAN,
    deadline DATE,
    required_documents JSONB NOT NULL CHECK (jsonb_typeof(required_documents) = 'array'),
    next_steps JSONB NOT NULL CHECK (jsonb_typeof(next_steps) = 'array'),
    warnings JSONB NOT NULL CHECK (jsonb_typeof(warnings) = 'array'),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
