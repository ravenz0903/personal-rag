# Folio

A personal workspace for tasks, habits, grades, career applications, and a document assistant. The interface takes inspiration from Notion's dark theme: a quiet sidebar, flat panels, simple lists, and explicit confirmation before captured records are saved.

The Calendar page combines dated tasks and assessments in a month view. Navigate months, select a day, filter record types, and create a task with that date already filled in. Its agenda supports task completion and assessment scoring. Personal tasks appear only when Personal is explicitly selected. Dates use the device's timezone; undated tasks remain in the task list.

## Run locally

Start the API with `./mvnw spring-boot:run`. In `frontend`, run `npm ci` and `npm run dev`. Open http://127.0.0.1:5173. Java 21+ and Node 22+ are required.

Structured forms work without an AI key. Gemini is required for Academic/Career indexing, natural language capture, and Assistant answers. Local data is stored in ignored `data/`. Local development is bound to 127.0.0.1 with sign-in disabled; the cloud profile requires Supabase authentication.

## Deploy online for free

See [DEPLOYMENT.md](DEPLOYMENT.md) for the concrete Vercel Hobby + Render Free + Supabase Free + Gemini free-tier setup. Deployment needs your provider accounts and environment settings. Free usage limits and service sleep still apply.

## How it works

- React/Vite calls the Spring Boot API using a Supabase bearer token. Only the configured workspace owner is accepted.
- Tracker records live in PostgreSQL. Grades use deterministic decimal arithmetic; habits use their saved timezone and schedule.
- Capture returns a validated, temporary confirmation card. Approval writes the real record and an audit event; supported events can be undone.
- Academic/Career document text becomes 768-dimensional Gemini embeddings plus a BM25 inverted index. Retrieval combines both rankings, and chat returns source passages alongside the streamed answer.
- Original files live in private Supabase storage. Personal files are stored without remote AI indexing; general views exclude Personal until explicitly selected.
- Provider errors remain errors: production never substitutes deterministic hash embeddings or made-up answers. The legacy Ollama class is retained only for compatibility with existing test doubles.

## Check changes

Run `./mvnw test` at the root and `npm test` plus `npm run build` in `frontend`. AI transport tests use a local HTTP server, so they consume no external quota. Cloud PostgreSQL, actual Gemini model responses, and provider deployment must be verified after configuring the accounts.
