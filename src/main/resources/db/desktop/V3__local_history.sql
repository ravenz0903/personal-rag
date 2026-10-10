CREATE TABLE local_history(id UUID PRIMARY KEY, record_id UUID NOT NULL, kind VARCHAR(16) NOT NULL, domain VARCHAR(32) NOT NULL, action VARCHAR(16) NOT NULL, before_json CLOB, after_json CLOB NOT NULL, expected_revision BIGINT NOT NULL, undone BOOLEAN NOT NULL DEFAULT FALSE, timestamp TIMESTAMP WITH TIME ZONE NOT NULL);
CREATE INDEX idx_local_history_record ON local_history(record_id,timestamp);
