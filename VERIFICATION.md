# Local Windows beta verification

This records the current verification position, not a certification of release readiness.

## Completed checks

- Backend suite: 458 tests passed, with no failures, errors, or skipped tests. A subsequent focused run of 18 tests also passed.
- Frontend suite: 9 tests passed. Desktop suite: 7 tests passed.
- Bundled-runtime smoke: private API access, rejection of missing authentication tokens, static interface, offline document storage, backup, disabled AI, and graceful shutdown.
- Desktop interface smoke: task board and reviewed edits, notes autosave, failed-save draft recovery and explicit retry, four calendar views, events, search navigation, progress, settings, keyboard search, and a narrower layout.
- Scope switch: Personal tasks and an open Personal note disappear immediately when switching to combined Academics/Career.
- Renderer: no external network requests during the exercised workflows. Scrollbar width measured at 4px.
- Backup round trip: a streamed archive restored into a separate disposable workspace while retaining the tested note's ID and saved content.
- Real launcher: hidden launch, closing to tray, backend remaining alive while hidden, and private desktop actions.
- Scheduled reminder: Windows acknowledged a notification while the Folio window was closed. Visible toast display and click-through remain separate checks.
- Automated migration and record checks cover original-schema upgrades, preserved IDs/dates/vectors, repeat migration behavior, stale edits, recurrence, daylight-saving boundaries, guarded undo, and retained habit logs.

Smoke checks use disposable workspaces rather than personal records.

## Required before a stable release

- Refresh the installer with the final source changes and repeat relevant smoke checks.
- Install on a clean Windows machine without system Java or Node.js.
- Verify installation, update, uninstall, and reinstall preserve user data.
- Verify real sleep/wake reminder catch-up, visible notifications, and notification clicks opening the correct record.
- Exercise the complete restore confirmation and recovery interface manually, including failure recovery.
- Complete remaining edit edge-case checks and a desktop accessibility/usability review.

The generated installer is an unsigned beta preview. Passing automated checks does not replace these outstanding device and installation checks.
