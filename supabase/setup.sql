-- Run in Supabase's SQL editor BEFORE connecting the backend for the first time.
-- App data is backend-only; browsers use Supabase for login, never direct table access.
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA extensions;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    REVOKE ALL ON TABLES FROM anon, authenticated;
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES ('documents', 'documents', false, 5242880, ARRAY['application/pdf', 'text/markdown'])
ON CONFLICT (id) DO UPDATE SET public = false, file_size_limit = 5242880,
    allowed_mime_types = ARRAY['application/pdf', 'text/markdown'];
-- Do not add browser upload/download policies to this bucket.
-- CloudDatabaseGuard enables RLS and revokes browser privileges on each application table at startup.
