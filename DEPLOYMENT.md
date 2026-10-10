# Folio: free online deployment

> The active project direction is now a local Windows desktop app. Use [DESKTOP.md](DESKTOP.md) for building, running, reminders, data migration, and backups. The cloud setup below is retained as a reference and is not required for the desktop edition. AI is postponed in desktop mode.

Folio is a single-owner workspace. The React interface runs on Vercel Hobby; the Java API runs on Render Free; Supabase Free holds authentication, PostgreSQL vectors and private originals. Gemini's free API tier handles Academic/Career capture, embeddings and cited answers. Your computer can be off after deployment.

There is no paid provider fallback. Keep Gemini on an **unbilled project**, choose only free plans, and use the supplied hosting subdomains. Free quotas can stop AI requests, Render sleeps when idle, and Supabase can pause inactive free projects. These services do not promise unlimited usage or permanent free availability. Grade calculations and tracker forms use no model calls. Gemini free-tier content may be used to improve Google's products; do not upload sensitive Academic/Career documents. Personal files and capture text are excluded from Gemini by default.

## 1. Supabase

1. Create a Free project. Save the database password privately.
2. Run [supabase/setup.sql](supabase/setup.sql) in SQL Editor **before starting the backend**. It creates a private `documents` bucket and prevents browser roles from receiving grants on new application tables.
3. Authentication → Users → Add user: create your email/password account. Copy its user UUID as `TRACKER_OWNER_ID`. Disable public sign-ups in Authentication settings; the backend also rejects every other user.
4. Authentication → Signing Keys: use an asymmetric ES256 or RS256 key. Legacy HS256 tokens are deliberately unsupported. Sign in again after switching keys.
5. Copy Project URL and publishable key (legacy anon key also works) for `SUPABASE_URL` and `SUPABASE_PUBLIC_KEY`. Copy the **service role** key for `SUPABASE_SERVICE_KEY`, kept only in Render. Never use the service key in Vercel/frontend code.
6. Connect → Session pooler: use its host, port **5432**, and `postgres.PROJECT_REFERENCE` username. Set `DATABASE_URL=jdbc:postgresql://HOST:5432/postgres?sslmode=require`. Set username/password separately. Use the session pooler, rather than transaction pooling, for the persistent Spring connection pool.

The cloud backend creates its tables and enables row-level security with no browser policies. It uses the PostgreSQL owner role, which bypasses RLS; all user requests pass the backend's signed JWT/owner validation. This design supports one owner, not multiple independent tenants.

## 2. Gemini

Create an API key in Google AI Studio for a project with **no billing enabled**. Enter it as `GEMINI_API_KEY` in Render. Defaults are `gemini-2.5-flash` and `gemini-embedding-2`, with 768-dimensional normalized embeddings. Confirm free-tier availability in your region before enabling the key.

The application caps provider calls to 100/day and 20/minute per running process, with two requests concurrently. These counters reset on restart and are usage guards, not provider billing controls. Provider quota responses stop requests with an explicit error; no automatic paid fallback or fabricated embeddings are used. Files are limited to 5 MB and 16 extracted sections per upload. Scanned PDFs need OCR elsewhere first. Concurrent ingestion can consume several quota units even if one section fails.

## 3. Render backend

Create a free Web Service from this repository, using Docker at the repository root. Alternatively import `render.yaml` as a Blueprint and confirm the free plan. Configure all values in [.env.example](.env.example) in Render's Environment settings. Do not commit a filled environment file. Use `SPRING_PROFILES_ACTIVE=cloud`.

After deployment, `https://YOUR_API.onrender.com/api/health` should return `{"status":"ok"}`. `/api/tracker/tasks?domains=ACADEMICS` must return 401 without a bearer token. Cloud startup refuses missing owner/auth/database settings; local authentication is disabled only in the `dev` profile bound to 127.0.0.1.

## 4. Vercel frontend

Import this repository into Vercel. Select **Hobby** and set Root Directory to `frontend`, Framework to Vite, Build Command to `npm run build`, and Output Directory to `dist`. Add `VITE_API_BASE_URL=https://YOUR_API.onrender.com`. Deploy, then set Render's `FRONTEND_URL` to the exact Vercel URL (no trailing slash), and restart Render. The app retrieves Supabase public configuration from the backend; only the API URL is required in Vercel.

Open the Vercel URL and sign in using the Supabase account created above. Test a task preview/approval, a habit log, a course/category/assessment/score, a private download, and a short Academic note followed by an Assistant question. Refresh to verify persistence. Check that an unrelated Supabase user receives 401 and cannot access this workspace.

## Local development and checks

Java 21+ and Node 22+ are required. Run `./mvnw spring-boot:run` at the root, and `npm ci` then `npm run dev` in `frontend`. Open http://127.0.0.1:5173. Local data persists in ignored `data/`; no AI key is necessary for structured tracker forms or Personal uploads. To test Gemini locally, set `GEMINI_API_KEY` in your shell before starting Java.

Run `./mvnw test` and `npm run build` in `frontend`. Tests use explicit legacy test doubles, not live Gemini credentials, so benchmark figures do not represent cloud end-to-end speed.

## Existing Ollama data

Do not copy old hash-based/Ollama vectors into the cloud database. Upload originals again to generate Gemini vectors. The new document metadata records the embedding model and forces re-indexing when it changes. If changing embedding models in a populated database, re-ingest **every indexed document before using Assistant**; dimensions alone do not make model spaces compatible.

Originals uploaded under a new content hash leave previous object versions in storage. Remove unused objects through Supabase if storage approaches the free limit; current downloads use the recorded object path. Unconfirmed capture cards expire after 15 minutes and are lost when the backend restarts. Render wake-up can take about a minute. There is no uptime workaround that keeps the free service artificially awake.

## Reference policies

- [Gemini pricing and data use](https://ai.google.dev/gemini-api/docs/pricing)
- [Render Free limits](https://render.com/docs/free)
- [Supabase pricing](https://supabase.com/pricing)
- [Vercel Hobby](https://vercel.com/docs/plans/hobby)
# Assistant actions and closed-app reminders

The assistant's **Tasks & calendar** mode proposes new tasks and edits using Gemini. Approval saves them through the same audited confirmation flow as the task forms. Calendar entries use task deadlines. Personal tasks can be edited with the form without sending their text to Gemini.

Device notifications need an additional Supabase Cron/Edge Function setup. Follow [supabase/REMINDERS.md](supabase/REMINDERS.md) after deploying the backend; keep `REMINDERS_ENABLED=false` until the scheduler has been configured and tested. The scheduler runs independently of Render, uses encrypted browser push, and does not send email or SMS.
