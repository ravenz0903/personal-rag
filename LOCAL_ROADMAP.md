# Folio — local Windows edition

This replaces the earlier cloud plan. The first release is a free Windows app with a single workspace per Windows user. It runs the existing React interface and Java backend locally, bundles its runtime, and stores records and original documents under `%APPDATA%/Folio`. Google accounts, Supabase, Vercel, Render, browser push, and AI provider selection are postponed. No AI key is required.

## 1. Desktop and data foundation

- Installer, bundled Java runtime, loopback-only backend, per-launch API token, sandboxed renderer, and one running app instance.
- Closing the window keeps the app in its tray; opening the tray restores the window. Quit stops the backend. Optional startup with Windows is controlled in Settings.
- Display name, workspace timezone, Sunday/Monday week start, and reminder preference are stored in the local database.
- Versioned schema migrations replace automatic schema changes. Back up an existing database before a pending migration and validate the final schema.
- Preserve original IDs, relationships, grades, logs, documents, change history, embedding metadata, and existing vectors.
- Save complete backups with checksums. Validate restores in a staging folder, create a fresh recovery backup, retain the previous data folder, and restart the backend against the restored database. An interrupted restore is recovered before the next launch.

## 2. Reminders

- Windows notifications for task and standalone-event reminders while the window is closed and the tray remains running.
- Persistent delivery acknowledgements, claim leases, at most three attempts, cancellation checks, and catch-up for reminders missed within the previous 24 hours.
- Check on resume and while running; exclude completed, cancelled, archived, and rescheduled records before showing a reminder.
- Generic notification text; notification links open the relevant record and area. Notification tests and delivery status are available in Settings.
- Reminders cannot run after Quit, while the PC is off, or while it is asleep. Windows Do not disturb can hide a notification that Windows has accepted. A crash between displaying and acknowledging a notification may repeat it.

## 3. Tasks and calendar

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

- Local Tiptap editor: headings, paragraphs, bold/italic, lists, checklists, and safe links. Pin, tag, archive/restore, and export Markdown.
- Autosave with revision checks, saving/error status, and local failed-save drafts. Recovered drafts require review/retry and cannot silently overwrite a newer revision. Drafts are separated by the Windows workspace and cleared on a successful restore.
- Paginated workspace search covers tasks, notes, events, courses, applications, and filenames; Ctrl/Cmd+K, keyboard results, and actual record opening.
- Today includes overdue/today tasks, today's events, upcoming assessments, and existing habit check-ins. Progress offers 7/30-day task completions, scheduled-day habit adherence, weighted grades, and application stages.
- Edit/archive habits, courses, and applications without dropping their historical relationships. Structured exports contain records, Markdown notes, and a document manifest; complete backups include originals.
- Dark minimal interface, 4px scrollbars, working controls, accessible dialogs, and layouts that adapt to narrower windows.
- The combined Academics/Career scope excludes Personal; selecting Personal or following its specific notification is explicit.

## 5. Verification and release criteria

Implementation is not equivalent to a fully verified public release. See `VERIFICATION.md` for evidence and remaining gates.

1. **Data:** migrate an existing schema, preserve IDs/vectors/dates, rerun safely, verify checksums, round-trip a backup, and recover an interrupted restore. Installation, update, and uninstall must retain user data.
2. **Offline and privacy:** run without credentials; store/download originals without AI calls; reject unauthenticated local API requests; keep the renderer sandboxed; verify no external renderer requests and no Personal records in combined views.
3. **Records:** verify task board/list state, subtask depth/area rules, recurrence boundaries, stale forms, duplicate completion, archive/restore, and safe undo with related logs preserved.
4. **Time and notes:** check all-day dates, month/leap boundaries, DST gaps/overlaps, recurring clock anchors, invalid ranges, autosave failure/reload/retry, conflicting edits, malformed editor content, and link handling.
5. **Device lifecycle:** verify close-to-tray and a scheduled reminder while hidden, explicit Quit, wake catch-up, notification click-through, expired/changed reminders, retries, and optional startup with Windows.
6. **Installer and interface:** build an installer with its own runtime; test installation on clean Windows without Java/Node, update an existing installation, uninstall/reinstall with data retention, keyboard access, and smaller-window layouts.

AI, sync, accounts, teams, shared pages, external calendar integration, custom database builders, recurring calendar events, paid services, and automatic updates remain deferred. The root README remains removed. Additional feature ideas should extend this roadmap without changing the local/offline guarantees.
