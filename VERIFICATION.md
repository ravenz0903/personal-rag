# Local Windows beta verification

This records the current verification position, not a certification of release readiness.

Updated 11 October 2026. The earlier beta implementation and public README/screenshots were pushed in commit `2d60008`; current follow-up changes are not yet pushed. The active delivery checklist is [PLAN.md](PLAN.md).

## Completed checks

- Backend suite: 458 tests passed, with no failures, errors, or skipped tests. A subsequent focused run of 18 tests also passed.
- Latest frontend suite: 9 tests passed. Latest desktop suite: 12 tests passed.
- Bundled-runtime smoke: private API access, rejection of missing authentication tokens, static interface, offline document storage, backup, disabled AI, and graceful shutdown.
- Desktop interface smoke: task board and reviewed edits, notes autosave, failed-save draft recovery and explicit retry, four calendar views, events, search navigation, progress, settings, keyboard search, and a narrower layout.
- Scope switch: Personal tasks and an open Personal note disappear immediately when switching to combined Academics/Career.
- Renderer: no external network requests during the exercised workflows. Scrollbar width measured at 4px.
- Backup round trip: a streamed archive restored into a separate disposable workspace while retaining the tested note's ID and saved content.
- Real launcher: hidden launch, closing to tray, backend remaining alive while hidden, and private desktop actions.
- Scheduled reminder: Windows acknowledged a notification while the Folio window was closed. Visible toast display and click-through remain separate checks.
- Automated migration and record checks cover original-schema upgrades, preserved IDs/dates/vectors, repeat migration behavior, stale edits, recurrence, daylight-saving boundaries, guarded undo, and retained habit logs.
- Follow-up desktop regression: opening a subtask while task-list reads are held preserves its parent before and after options arrive, through review and saving.
- Follow-up keyboard check: the task dialog receives focus, traps forward Tab at its last control, and restores focus to the task button on Escape. Preview approval receives focus.

Smoke checks use disposable workspaces rather than personal records.

## Local AI evaluation groundwork

- AI is still disabled in Folio; no new installer was built.
- GPU comparison completed on the RTX 4060 laptop: the same strict checks remained 3/12, 5/12, and 6/12 for Qwen2.5 1.5B, Qwen3.5 2B, and Qwen3 4B. Median request times were 1.25, 2.31, and 1.59 seconds respectively. These are single synthetic runs, not broad model ratings.
- Two Qwen3 4B QLoRA experiments completed on synthetic data, using a pinned original base with checksum-verified weights. The 20-step adapter improved its 12 structured validation checks from 6/12 to 7/12 but remained at 8/12 on the original regression benchmark in the Transformers NF4 runtime. The 96-step refined experiment improved its own structured validation from 8/12 to 11/12 but also remained at 8/12 on the original benchmark. It corrected ambiguous-record handling and the document-injection action, but regressed on the abstention flag and retained citation-format/reminder-action failures. Neither validation set was optimized. Both sets remain small templated experiments; see `LOCAL_AI.md` for the limits.
- Manual review of the refined validation output found a wrong event start time (11:30 instead of 11:00). A completed training run is not release qualification. No personal records were used and no proposed actions were executed.
- Third training run: 200 unique examples from 512 varied synthetic rows completed on the GPU. Structured validation improved 8/16 → 15/16 and the unchanged regression set improved 8/12 → 9/12. Separately authored stricter probes improved 0/16 → 7/16. Transfer is insufficient: reminder/date/ambiguity/abstention/citation/injection failures remain. Eight of nine failed outputs were rejected by prototype preflight; the remaining abstention error passed structural validation. AI release qualification failed; no model was enabled or packaged.
- Experimental proposal-preflight unit tests cover document writes, ambiguous and inaccessible edits, Personal/cross-workspace citations, malformed source metadata, enum values, nonexistent dates, and invalid event ranges. This preflight is not wired to the backend and does not establish semantic correctness or real API authorization. At that stage the desktop suite passed 12 tests and dataset suite passed 4 tests; the runtime-contract dataset checks bring the latest dataset suite to 6 passing tests.
- Dataset integrity checks passed (2 tests); asset verification and benchmark scoring checks passed (2 tests). Training environments, base weights, adapters, logs, and generated Python caches remain excluded from Git.
- Earlier desktop suite: 9 tests passed before the additional proposal checks were added.
- Exploratory CPU-only model runs completed on synthetic records: Qwen2.5 1.5B Q4_K_M passed 3/12 strict checks, Qwen3.5 2B Q8_0 passed 5/12, and Qwen3 4B Q4_K_M passed 6/12. No proposals were executed and no personal records were read.
- These results do not qualify any model for release. See [LOCAL_AI.md](LOCAL_AI.md) for timings, allocation limits, test caveats, and the bundled-runtime/low-end-device gates.

## Required before a stable release

- Build the authorized private offline beta after model qualification, Windows stability, and integration checks pass; refresh bundled resources and repeat checks against the final build.
- Install on a clean Windows machine without system Java or Node.js.
- Verify installation, update, uninstall, and reinstall preserve user data.
- Verify real sleep/wake reminder catch-up. Human clicks on both scheduled task/event notifications now open the correct record; see the latest evidence below.
- Exercise the complete restore confirmation and recovery interface manually, including failure recovery.
- Continue remaining edit edge-case checks; delayed-loading subtask parent preservation is now fixed and covered by the desktop regression.
- Complete a desktop accessibility/usability review.

The generated installer is an unsigned beta preview. Passing automated checks does not replace these outstanding device and installation checks.

## Current development follow-up

- Qwen3 4B runtime-contract adapter completed 400 additional steps from the varied adapter. Structured validation passed 12/12 and the unchanged original benchmark passed 12/12. This meets the original-test target only; see `LOCAL_AI.md` for manual-review limitations and broader qualification. No model was enabled, no personal records were used, and no proposals were executed.
- Added six total dataset integrity checks, including normalized prompt separation, real date arithmetic, and grounded/abstaining output labels. All six passed.
- Course and event stale-save interface regressions passed against a disposable real backend: newer records were preserved, local form text remained available, errors appeared inside the dialog, and dismissal refreshed the visible record. A course reopened from search saved successfully and stayed closed after refresh.
- Frontend build, 9 frontend tests, and 12 desktop tests passed. No installer or launcher changes were made.
- Screenshot review caught Chromium ignoring the intended custom scrollbar because `scrollbar-color` was non-auto. Reset both standard scrollbar overrides for Chromium; reran the disposable interface/backup smoke successfully and visually confirmed the narrow 4px rail with no arrow buttons. This corrects the earlier CSS-only measurement, which did not prove the rendered width.
- Final paired separate-probe evaluation: unchanged base 0/16, runtime-contract adapter 8/16 (previous adapter 7/16). Date-boundary, overnight-event, missing-time, invalid-date, citation, and abstention failures remain; five of eight failed outputs were rejected by prototype preflight. The original target passed, but AI release qualification remains unmet. Both sets are development evidence, not general accuracy estimates.

## Authorized beta follow-up: actual device and stability evidence

- The approved delivery order is model qualification, Windows stability, integrated workflows, then a private offline beta for the target laptop. Installer and necessary launcher changes are authorized.
- Real launcher smoke passed cold hidden startup, four close/reopen cycles, a genuine second-process single-instance check, default-Cancel restore, invalid ZIP rejection without changing records, successful restore with retained prior data and recovery archive, and explicit Quit stopping every backend process. Dialog choices were supplied by the harness; this is not a human visual-dialog review.
- The user clicked two actual scheduled Windows notifications. The task notification opened the intended task editor; the event notification opened the intended event editor. No click was simulated. Real sleep/wake catch-up remains pending.
- Undo now locks the current record before checking later audit entries. A focused 17-test run passed after that change; four new checks cover stale habit/course/application edits, assessment relationships, conflicting note/event undo, and course undo preserving assessments.
- Latest client-focused backend run: 13 tests passed (five local AI client, four desktop integration, four local-record stability). Latest desktop suite: 17 tests passed, including four managed-runtime tests. Latest dataset suite: eight checks passed.
- Local AI client and main-process inference manager are groundwork, not connected production inference. Fake runtime checks cover demand loading, model switching, idle unloading, GPU load failure to CPU, crash recovery, queue bounds, cancellation, private bridge authentication, browser-origin rejection, forbidden tool controls, and shutdown. No exact GGUF execution is established by these tests.
- Official llama.cpp b11429 Windows CPU/Vulkan archives were downloaded with verified publisher sizes and SHA-256 values. The CPU executable reports its version successfully. Actual inference, GPU use, fallback with real models, licensing/package checks, and installer lifecycle remain outstanding.
- Frozen 100-case synthetic developer-authored qualification fixtures were created before harder training. They are separate from optimization examples and exact normalized prompt overlaps are checked. They are not a blinded human benchmark; the original 12 and harder 16 remain unchanged development regression sets.

## Continued integration checks (test doubles; model not enabled)

- Full backend suite passed 467 tests before the later event/action/index additions. Subsequent focused runs passed 16 event/client tests, 16 cancellation/date/record tests, nine hosted/local action tests, and 13 document/action/desktop tests; these are overlapping runs and must not be added into a total.
- Standalone event previews now create no record until approval, are single use, save actual calendar events with audit entries, reject invalid fields/ranges, and revalidate expired reminder times on commit.
- The local action controller uses application contract v2. Trusted date resolution handles leap-day tomorrow, explicit clock times, overnight ranges, and DST gaps/overlaps; invented timestamps are rejected. Edits require explicit task selection and retain the selected revision through approval. Combined scope cannot select/create Personal records. Tests use an explicit pure-Java fake, not the tuned model.
- Explicit cancellation interrupts backend inference, also handles cancellation before registration, rejects duplicate active request IDs, and does not interrupt a reused request thread after completion. Tested using real worker threads.
- Additive migration V5 introduces a separate model-identified index. Document tests verify original checksums, legacy embedding metadata and vector rows remain unchanged; incompatible namespaces do not mix; failed reindex retains the previous index; combined retrieval excludes Personal. Source-validation tests reject invented citation IDs and document-induced writes, expose only chosen source IDs, and return no citations when abstaining. This validates guards with a fake model, not factual accuracy of a real answer.
- A Windows native inference host was built and tested against a real harmless child process. Forcibly terminating the host closes its Windows job and terminates the child, preventing orphan inference processes even when cleanup callbacks do not run.
- GPU enumeration on the target laptop places integrated AMD graphics before the RTX 4060. The manager now selects the dedicated GPU explicitly rather than assuming device zero. Unit checks cover hybrid and integrated-only inventories; actual inference on each backend remains pending.

## Latest integrated development evidence — 11 October 2026

- Full backend suite: **484 tests passed**, zero failures/errors/skips. Earlier focused run counts overlap and are not added to this total.
- Frontend: nine tests and production build passed. The repeated disposable interface smoke covered delayed parent loading, conflict drafts, editor closure, Personal exclusion, all calendar views, search, keyboard focus, narrow layout, rendered 4px scrollbars, no external renderer requests, and backup round trip preserving note ID/content.
- Desktop: **22 tests passed**, including verified-asset gates, exact contract parity, proposal guards, managed runtime controls, and an actual child process writing after its output pipes were closed. The EPIPE notification-test crash is fixed by handling closed output streams and using retained log handles.
- Real Windows launcher smoke passed cold hidden startup, four close/reopen cycles, genuine second-instance handling, restore cancellation, invalid ZIP rejection, successful restore retaining recovery copies, and startup-failure rollback from a checksum-valid but unreadable database. Explicit Quit stopped backend children. Dialog decisions were automated; human visual review and interrupted-restore testing remain pending.
- Actual CPU and dedicated RTX 4060 Vulkan execution of the pinned Nomic embedding GGUF produced finite 768-dimensional vectors; related text ranked above unrelated text. The real private Java/index smoke retained original checksums/metadata, prevented Personal retrieval in combined scope, rejected unauthenticated access, and saved a task after deliberate chat-runtime failure.
- The native inference host was tested with real harmless processes: forcibly killing the host terminated its child; forcibly killing the parent terminated both host and child. These checks do not establish final chat-model memory/performance or real GPU-to-CPU fallback.
- Qwen3 4B v7: **12/12 original, 16/16 harder, 83/100 frozen synthetic qualification**, with zero flagged unsafe outputs. The release gate failed. No chat export, enabled ordinary-build assistant, or complete offline AI installer exists.
- Pinned Temurin Java 21 preparation verified the archive and runtime version. Final package redistribution/source review and clean-machine installer checks remain pending. Installer-template syntax compilation used dummy assets, not a released application.

Real user clicks on scheduled task/event notifications passed earlier. Real sleep/wake, final real-chat workflows, broader hardware compatibility, and installer update/uninstall/reinstall remain open release criteria. Generated artifacts and detailed local logs are retained outside Git.

### Pre-push recheck

The 22 desktop checks passed again, and nine focused backend action/authentication checks passed after the final reminder-intent wording change. The real private embedding/index integration also passed with the newly bundled Temurin Java 21 runtime, including a core write after intentional chat failure. No new model training or installer build was performed during this repository update.
