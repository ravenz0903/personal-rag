# Folio for Windows

Folio now targets a local Windows desktop app. Cloud deployment and AI selection are postponed. The existing React interface and Java backend run together without a browser, account, API key, or internet connection after installation.

## Build and run

Install Node.js 22.12+ and JDK 21 or newer on the Windows build computer. Install frontend dependencies with `npm ci` in `frontend`. Run `npm ci` in `desktop`, then `npm run prepare` and `npm start` there. Preparation builds/stages the backend and frontend and downloads/verifies the pinned Java runtime; it skips backend tests. `npm run package` builds an unpacked Windows application under `desktop/release/win-unpacked`. End users use the bundled runtime and do not install Java or Node.

The AI-enabled private-beta installer workflow is `node desktop/scripts/build-beta.cjs target/qualified-export` from the repository root, with the example directory replaced by a genuinely qualified export. It requires verified model/runtime assets, license material, and the project-local Inno Setup compiler; output goes to `target/private-beta`. There is no qualified chat export yet. Bare `npm run dist` has no export argument and is expected to reject the build. Historical core-only QA installers are separate from this gated release workflow.

To avoid sharing the normal workspace during development, launch with an explicit directory, for example `npm start -- --user-data-dir="C:\Temp\folio-dev-workspace"` from `desktop`. Ordinary development and packaged launches use the same default workspace. Verify the actual path through Settings before entering test records.

Run `npm test` in `desktop` for launcher checks and `node scripts/smoke.cjs` after preparing resources to verify the bundled Java runtime, private API, frontend serving, offline uploads, ZIP backups, and graceful shutdown. Smoke checks use a disposable temporary workspace, never your own database. Run `mvnw.cmd test` at the repository root for the backend suite and `npm test` in `frontend` for calendar/timezone checks. Run `desktop/node_modules/.bin/electron.cmd desktop/scripts/ui-smoke.cjs` from the root after preparing resources for the sandboxed UI and backup round-trip checks. `desktop/scripts/lifecycle-smoke.cjs` exercises the real launcher, tray behavior, and a scheduled Windows notification in a disposable workspace.

Close the window to keep Folio running in the tray. Double-click the tray icon to reopen it. Choose **Quit Folio** to stop the backend and reminders. Settings provides workspace preferences, reminder status, a test notification, optional startup with Windows (installed builds), a data-folder shortcut, backup/restore, and export. Windows notification permissions, Do not disturb, sleeping, and shutdown affect delivery. Missed reminders catch up for up to 24 hours after waking/restarting. Delivery history survives app restarts; a crash between showing a notification and saving its acknowledgement can repeat it.

## Data and migration

Desktop records live in the application's user-data directory (`%APPDATA%/Folio` for packaged builds), in `data/tracker.mv.db` and `data/documents/`. Development and packaged Folio both use this folder; disposable verification scripts use separate temporary workspaces. Use **Open data folder** to find the exact one. Installation and updates do not include or replace personal data.

To transfer existing development records:

1. Stop the old Java backend and quit desktop Folio from the tray. Never copy an open database.
2. Copy the project's entire `data` folder to a separate backup location first.
3. Open Folio's data folder, then quit it again. Back up its existing `data` folder if it contains records.
4. Copy the stopped project's `data/tracker.mv.db` and entire `data/documents` folder into Folio's `data` folder. Do not merge two databases or overwrite records you want to retain.
5. Start Folio and verify task/course/habit counts and document downloads. Keep the source backup.

**Save backup** uses H2's live backup command. It writes the complete archive on disk, then streams it to the chosen file rather than buffering the workspace in memory. Archives contain a nested database ZIP, originals, SHA-256 file manifest, and recovery instructions. **Restore backup** validates paths, sizes, CRCs, manifest hashes, and document content hashes in an isolated staging folder before asking to replace the workspace. It saves a fresh backup under `restore-backups/`, retains `data-before-restore-*`, stops Java, and validates the restored database by restarting it. Startup recovers a replacement interrupted by a crash. Restore accepts current manifest-bearing backups; older backups can still be restored manually after quitting Folio.

Before each pending database migration, `data/migration-backups/` receives a consistent database ZIP. Keep it until the updated app has been verified. If a migration fails, quit Folio, retain the failed data folder, and restore the latest pre-migration database ZIP to a separate replacement `data` folder together with the unchanged originals. Correct the migration before restarting; do not repair a failed migration by deleting its history in the live database.

**Export workspace** writes structured records, Markdown notes, and a document manifest. Original files remain individually downloadable from Documents. A complete backup also includes every original. Backups and exports include all areas; keep them private.

## Security and current scope

The backend listens only on loopback and uses a random per-launch secret. Ordinary desktop builds block AI endpoints; optional managed AI activates only behind verified-asset and qualification gates. The renderer has no Node access, exposes only narrow desktop actions, and cannot navigate to external sites. This protects against browser-origin requests; it does not protect data from malware or another person logged into the same Windows account. The local database and backups are not encrypted by Folio.

PDF and Markdown uploads store originals without embeddings. Existing indexed originals remain intact; replacing an indexed original with changed contents requires a new filename while AI is postponed. Personal remains outside the combined Academic/Career view.

The local edition includes task boards, subtasks, tags, filters, recurring tasks, archive/restore, four calendar views with standalone events, formatted notes with autosave/recovery, workspace search, progress, record editing, and guarded undo. See `LOCAL_ROADMAP.md` and `VERIFICATION.md` for the feature scope and release gates. Google sign-in, independent cloud accounts, Supabase push setup, and Vercel/Render deployment are no longer required for this local edition. The root README provides a public feature overview and beta screenshots.
