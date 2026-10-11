-- Separate namespace: never rewrite or mix legacy vector_chunks/embedding metadata.
CREATE TABLE local_ai_indexes (
    document_id UUID NOT NULL REFERENCES document_records(id) ON DELETE CASCADE,
    embedding_space VARCHAR(200) NOT NULL,
    content_hash VARCHAR(128) NOT NULL,
    chunk_count INT NOT NULL,
    indexed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY(document_id,embedding_space)
);
CREATE TABLE local_ai_chunks (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    embedding_space VARCHAR(200) NOT NULL,
    domain VARCHAR(32) NOT NULL,
    page_number INT NOT NULL,
    heading_path VARCHAR(1024),
    content CLOB NOT NULL,
    embedding_bytes VARBINARY(4096) NOT NULL,
    FOREIGN KEY(document_id,embedding_space) REFERENCES local_ai_indexes(document_id,embedding_space) ON DELETE CASCADE
);
CREATE INDEX idx_local_ai_scope ON local_ai_chunks(embedding_space,domain);
