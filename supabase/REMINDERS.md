# Device reminder setup

Reminders do not depend on Render staying awake. Supabase Cron invokes the `reminders` Edge Function every minute. The function claims due tasks, rechecks their current status and reminder time, then sends encrypted Web Push to registered devices. Completed, cancelled, and rescheduled tasks are skipped. Notification text is generic, including for Personal tasks: titles and descriptions never appear on the lock screen or go to Gemini for delivery.

## Setup after creating the provider accounts

1. Deploy the backend once so Hibernate creates `tasks.remind_at` and `push_subscriptions`. Keep `REMINDERS_ENABLED=false` until setup is complete.
2. Generate one VAPID key pair on your computer with `npx --yes web-push@3.6.7 generate-vapid-keys --json`. Keep the private key out of Git and this chat. Retain the pair: changing it requires devices to subscribe again.
3. Install the Supabase CLI, sign in, and link this repository to your Supabase project. In Supabase Edge Function secrets, set `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT` (a valid `mailto:` contact address), and `REMINDER_CRON_SECRET` (a long randomly generated secret). Supabase supplies `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` to the function. Never expose the service role or private keys in Vercel frontend variables.
4. Deploy with `supabase functions deploy reminders`. The repository's `supabase/config.toml` disables gateway JWT verification for this function because its handler authenticates the private cron header before accessing the database.
5. Enable `pg_cron` and `pg_net` in the Supabase Database Extensions screen. Add Vault secrets `folio_url` with your `https://PROJECT.supabase.co` URL and `folio_reminder_secret` with the same `REMINDER_CRON_SECRET`. Use the dashboard's secret fields; do not paste secrets into committed SQL.
6. Run `supabase/reminders.sql` in the SQL editor. It creates the backend-only delivery table, a service-role-only claiming function, and two scheduled jobs. Re-running the named schedules updates those jobs rather than creating differently named duplicates.
7. In Render, set `VAPID_PUBLIC_KEY` to the public key and `REMINDERS_ENABLED=true`, then redeploy. The private VAPID key belongs only in Supabase. Deploy the frontend with its included service worker and manifest.
8. Sign into Folio on the device that should receive reminders. In Settings, choose **Enable on this device**, then grant the browser notification permission. On iPhone/iPad, first add the app to your Home Screen and open the installed app. Repeat on each device; signing out unregisters that device.

## Verify before relying on reminders

Create and approve a task with a reminder two minutes in the future. Close the app, keep the device connected, and check for the generic notification. Open it and verify it leads to Tasks and requires sign-in when signed out. Repeat after completing or rescheduling a task; its old reminder should not be delivered. Inspect the Supabase Cron job history, Edge Function responses, and `reminder_deliveries.sent_at` if delivery fails. HTTP 401 means the shared cron secrets do not match. Keep reminder setup disabled if the function or cron is not healthy.

## Limits and semantics

The minute schedule adds up to roughly one minute before processing; OS/browser settings and network conditions can delay delivery further. A powered-off device cannot display a notification until it reconnects. Push messages expire after one hour in the browser push service. The database catches up reminder schedules at most 24 hours late, attempts delivery at most three times per device with five-minute leases, and processes ten deliveries per invocation. Delivery is at least once: a crash after the push service accepts a message can cause a retry; stable notification tags replace matching notifications where the browser supports that behavior. An edit racing with a send already accepted by the push service cannot retract it.

The scheduler uses about 44,640 Edge Function invocations in a 31-day month, plus one daily SQL cleanup job. Check your current Supabase free quotas and project status before enabling it. Free projects, providers, and browsers are not a guaranteed alarm service; no paid fallback is configured. A free project paused by Supabase cannot run reminders. No cloud accounts, VAPID keys, cron jobs, or notification permissions have been provisioned by the code changes alone.

Official references: [Supabase scheduled functions](https://supabase.com/docs/guides/functions/schedule-functions), [function limits](https://supabase.com/docs/guides/functions/limits), [Web Push library](https://github.com/web-push-libs/web-push), [browser Push API](https://developer.mozilla.org/en-US/docs/Web/API/Push_API).

## Local checks

`node --test frontend/src/calendar.test.js supabase/functions/_shared/deliver-reminders.test.mjs` checks calendar scoping, delivery outcomes, and endpoint restrictions.

`npx --yes deno check supabase/functions/reminders/index.ts` checks the Edge Function. `npx --yes deno test --allow-read --allow-env supabase/tests/reminders.test.ts` runs the actual claiming SQL in an isolated in-memory PostgreSQL engine and verifies Web Push encryption in Deno. It does not configure cron or send notifications. The SQL tests verify leases, per-device deduplication, retry limits, rescheduling, completed/cancelled task exclusion, and browser-role denial. Supabase cron integration and actual device delivery still require the deployment check above.
