# Folio — local Windows edition

This replaces the earlier cloud plan. The first release is a free Windows app with a single workspace per Windows user. It runs the existing React interface and Java backend locally, bundles its runtime, and stores records and original documents under `%APPDATA%/Folio`. Google accounts, Supabase, Vercel, Render, browser push, and AI provider selection are postponed. No AI key is required.

**Updated: 11 October 2026. Current stage: implemented beta, completing release verification.** The implementation, public feature README, and three sample-data screenshots were pushed to `main` in commit `2d60008`. This status update does not imply that all release gates have passed.

**Latest direction:** [PLAN.md](PLAN.md) now controls delivery: harder Qwen3 4B training and qualification, Windows stability, local AI integration, then a private offline beta installer for the owner's laptop. Necessary launcher work and beta packaging are authorized; this supersedes the earlier pause on installers.

**Local AI research is now authorized:** evaluate a small, bundled model and Folio-managed inference runtime for a range of Windows laptops. No separate Ollama setup should be required for end users. Candidate evaluation and the integrated-installation contract are tracked in [LOCAL_AI.md](LOCAL_AI.md). AI remains disabled in the app until an exact runtime/model pairing passes quality, resource, and action-validation checks.

## Delivery status

| Area | Implementation | Verification position |
|---|---|---|
| Desktop and local data | Implemented; unsigned preview installer generated | Bundled runtime, API guard, migration tests, and backup round trip passed; installation lifecycle and manual recovery still pending |
| Tray reminders | Implemented | Actual task/event notification clicks opened the correct editors on the target laptop; real sleep/wake remains pending |
| Tasks and calendar | Implemented | Automated record/time checks and desktop interface smoke passed; final edit edge cases remain |
| Notes, search, and progress | Implemented | Autosave failure/recovery/retry, scope switching, search navigation, and progress smoke passed |
| Interface and documentation | Beta interface and public README complete | 4px scrollbar, keyboard search, and narrower layout checked; broader accessibility/usability review pending |
| Stable release | Pending | Final packaging and all remaining device/installer gates must pass |

The sections below describe implemented scope and the remaining completion gates. Avoid rebuilding completed features; focus next on the release work at the end of this plan.

## 1. Desktop and data foundation

**Status: implemented; migration and backup checks passed.** Clean-machine installation, update/uninstall data retention, and the complete manual restore/recovery flow remain open.

- Installer, bundled Java runtime, loopback-only backend, per-launch API token, sandboxed renderer, and one running app instance.
- Closing the window keeps the app in its tray; opening the tray restores the window. Quit stops the backend. Optional startup with Windows is controlled in Settings.
- Display name, workspace timezone, Sunday/Monday week start, and reminder preference are stored in the local database.
- Versioned schema migrations replace automatic schema changes. Back up an existing database before a pending migration and validate the final schema.
- Preserve original IDs, relationships, grades, logs, documents, change history, embedding metadata, and existing vectors.
- Save complete backups with checksums. Validate restores in a staging folder, create a fresh recovery backup, retain the previous data folder, and restart the backend against the restored database. An interrupted restore is recovered before the next launch.

## 2. Reminders

**Status: implemented; close-to-tray and Windows notification acknowledgement verified.** Actual visible notification display, correct-record click-through, sleep/wake catch-up, and installed startup behavior still need device checks.

- Windows notifications for task and standalone-event reminders while the window is closed and the tray remains running.
- Persistent delivery acknowledgements, claim leases, at most three attempts, cancellation checks, and catch-up for reminders missed within the previous 24 hours.
- Check on resume and while running; exclude completed, cancelled, archived, and rescheduled records before showing a reminder.
- Generic notification text; notification links open the relevant record and area. Notification tests and delivery status are available in Settings.
- Reminders cannot run after Quit, while the PC is off, or while it is asleep. Windows Do not disturb can hide a notification that Windows has accepted. A crash between displaying and acknowledging a notification may repeat it.

## 3. Tasks and calendar

**Status: implemented; automated checks and interface smoke passed.** The delayed-list subtask editor now retains its selected parent before and after options load, including review and saving. A desktop regression check covers this sequence.

- List and Board share task records; To do, In progress, and Done columns. Equivalent status selects support keyboard users.
- One level of subtasks and completion progress; parent completion is explicit.
- Tags, title/text filtering, priorities, statuses, due ranges, overdue filtering, sorting, archive/restore, and saved view preferences.
- Daily, weekdays, weekly, and monthly recurrence creates a separate next occurrence during completion. Skip missed dates, clamp short months, preserve reminder offsets, and reject duplicate/stale completion.
- Preserve the recurring clock anchor across daylight-saving changes. A nonexistent recurring clock time advances through that day's gap; later occurrences return to the original clock time. Manual input at nonexistent times is rejected; repeated times use the first occurrence.
- Undo must reject changes that would remove an edited recurring successor or overwrite a later change.
- Month, week, day, and 30-day agenda views combine task deadlines, assessment deadlines, and standalone events.
- Events support area, description, all-day dates, timed start/end, optional reminder, and archive/restore. All-day end dates are exclusive. UTC timed values are displayed in the workspace timezone.
- Drag task/event rescheduling and equivalent editors; assessment deadlines use their assessment form. Event moves preserve duration, and moves retain relative reminder offsets.

## 4. Notes, search, progress, and polish

**Status: implemented beta.** Notes recovery, calendar views, search opening records, progress, Personal-scope exclusion, keyboard search, and narrower layout were exercised. A broader accessibility and usability review remains required.

- Local Tiptap editor: headings, paragraphs, bold/italic, lists, checklists, and safe links. Pin, tag, archive/restore, and export Markdown.
- Autosave with revision checks, saving/error status, and local failed-save drafts. Recovered drafts require review/retry and cannot silently overwrite a newer revision. Drafts are separated by the Windows workspace and cleared on a successful restore.
- Paginated workspace search covers tasks, notes, events, courses, applications, and filenames; Ctrl/Cmd+K, keyboard results, and actual record opening.
- Today includes overdue/today tasks, today's events, upcoming assessments, and existing habit check-ins. Progress offers 7/30-day task completions, scheduled-day habit adherence, weighted grades, and application stages.
- Edit/archive habits, courses, and applications without dropping their historical relationships. Structured exports contain records, Markdown notes, and a document manifest; complete backups include originals.
- Dark minimal interface, 4px scrollbars, working controls, accessible dialogs, and layouts that adapt to narrower windows.
- The combined Academics/Career scope excludes Personal; selecting Personal or following its specific notification is explicit.

## 5. Verification and release criteria

Implementation is not equivalent to a fully verified public release. See `VERIFICATION.md` for evidence and remaining gates.

**Evidence so far:** the full backend run passed 458 tests; a subsequent focused run passed 18. Frontend and desktop suites most recently passed 9 and 12 tests respectively; training-data checks passed 6. Bundled-runtime, sandboxed interface, backup round-trip, and real-launcher checks also passed. These results do not substitute for the outstanding Windows installation and device checks.

1. **Data:** migrate an existing schema, preserve IDs/vectors/dates, rerun safely, verify checksums, round-trip a backup, and recover an interrupted restore. Installation, update, and uninstall must retain user data.
2. **Offline and privacy:** run without credentials; store/download originals without AI calls; reject unauthenticated local API requests; keep the renderer sandboxed; verify no external renderer requests and no Personal records in combined views.
3. **Records:** verify task board/list state, subtask depth/area rules, recurrence boundaries, stale forms, duplicate completion, archive/restore, and safe undo with related logs preserved.
4. **Time and notes:** check all-day dates, month/leap boundaries, DST gaps/overlaps, recurring clock anchors, invalid ranges, autosave failure/reload/retry, conflicting edits, malformed editor content, and link handling.
5. **Device lifecycle:** verify close-to-tray and a scheduled reminder while hidden, explicit Quit, wake catch-up, notification click-through, expired/changed reminders, retries, and optional startup with Windows.
6. **Installer and interface:** build an installer with its own runtime; test installation on clean Windows without Java/Node, update an existing installation, uninstall/reinstall with data retention, keyboard access, and smaller-window layouts.

## 6. Remaining work in delivery order

1. **Continue feature development and focused verification:** delayed-editor parent preservation is fixed and covered. Task dialogs now receive keyboard focus, trap Tab, return focus on dismissal, and focus approval when a preview appears. Course and event conflict dialogs now preserve the draft, display errors inside the dialog, and reload records on dismissal; search-origin record saves stay closed after refresh. These paths passed the disposable-workspace interface regression. Continue broader keyboard/accessibility review, undo edge cases, and future features as scope evolves.
2. **Finalize the authorized private beta package:** after the model and integration gates in PLAN.md pass, regenerate bundled resources and build a self-contained offline installer, then repeat runtime, interface, and launcher checks. Keep generated installers and model weights outside source control.
3. **Verify installed Windows behavior:** use a clean machine without Java/Node; check first launch, tray reopen, explicit Quit, optional startup, visible task/event reminders, notification navigation, and sleep/wake catch-up.
4. **Verify data survival and recovery:** install/update/uninstall/reinstall with sample records and originals, check counts and checksums, and manually exercise backup restore confirmation, failed restore recovery, and interrupted replacement recovery.
5. **Finish usability review:** keyboard navigation, focus, labels, smaller windows, empty/error states, and normal task/calendar/note workflows. Fix any findings and rerun affected checks.
6. **Release gate:** record outcomes in `VERIFICATION.md`, retain recovery backups/instructions, and publish a stable release only after every required gate passes. Until then, keep documentation and builds labeled beta.

## 7. Confirmed boundaries

Local AI is under evaluation; enabling it and packaging its assets remain gated by verification. Cloud AI, sync, accounts, teams, shared pages, external calendar integration, custom database builders, recurring calendar events, paid services, and automatic updates remain deferred. A feature-focused public README with three beta screenshots is now included at the user's request; screenshots use disposable sample records. Credentials, personal databases, backups, runtime bundles, model weights, and generated installers remain excluded from Git. Additional feature ideas should extend this roadmap without changing the local/offline guarantees.

Seven synthetic Qwen3 4B adapter experiments are complete. The latest reached **12/12 original regressions and 16/16 harder probes**, but **83/100 frozen qualification cases** falls below the 95/100 release target. No flagged unsafe outputs occurred in that set; this is not a guarantee of future correctness. AI remains disabled in ordinary builds. See `LOCAL_AI.md` for experiment history and `PLAN.md` for the current checklist.

Backend proposal validation and managed inference groundwork are now connected behind verified-asset gates. Pure-Java action tests and real local embedding/index checks pass; tuned-chat workflows, exported-model qualification, sleep/wake, and full installer lifecycle remain unverified. Preserve adapters and detailed reports locally, outside Git. Installer and necessary launcher work are authorized subject to the quality and stability gates in PLAN.md.
