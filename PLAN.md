# Folio — current implementation plan

Updated 13 October 2026. This is the main task checklist. Detailed evidence lives in [VERIFICATION.md](VERIFICATION.md) and [LOCAL_AI.md](LOCAL_AI.md); [LOCAL_ROADMAP.md](LOCAL_ROADMAP.md) describes the earlier scope.

## Current position

### Priority decision — application work first (13 October 2026)

The user set an 85% cutoff for deciding whether to continue training now. The latest fully evaluated candidate, V19, passed 82/100 fresh qualification cases, with zero flagged unsafe proposals on that set. Its unchanged historical results were 10/12 original, 12/16 harder and 58/100 qualification, including seven unsafe proposals in the historical qualification set. Manual source review also found factual errors. These finite-set pass rates do not establish a probability of eventual release success. V20 is incomplete and has no qualification score.

Training remains paused at the user's request. V20 reached logged step 97/1,457; its last saved resumable checkpoint is step 75. Preserve checkpoints, data, frozen evaluations and reports. Do not restart training until the user asks to return to it. The 85% cutoff selects the work priority; the existing model release requirements remain 95/100 with zero unsafe proposals and all required regression/runtime checks.

Application work order:

1. Reproduce remaining core-app issues in a disposable workspace; finish keyboard focus, labels, smaller-window layouts, empty/error states and actual save/edit flows. Fix crashes, hangs and data-loss risks first.
2. Finish reminder cancellation/duplicate checks and desktop lifecycle checks. Real sleep/wake and human notification/dialog checks require device evidence and must remain pending until performed.
3. Complete interrupted restore/recovery and stale-save/undo verification with disposable records, preserving the owner's data.
4. Build and verify the core application package with AI disabled: offline startup, bundled Java/backend, update and uninstall/reinstall data preservation, checksums and installation behavior. Treat this as core-app readiness; the final AI-enabled release still requires qualified assets and real model/app acceptance.
5. Return to model training later when requested, resume from the preserved checkpoint with optimizer state, then complete all qualification, factual review, export and integrated runtime checks.

Detailed evidence: `target/folio-model-readiness-assessment-v19.json` and the immutable V19 evaluation/manual-review reports. This decision does not claim either the core application or the AI-enabled release is finished.

- [x] Local Windows workspace: tasks, board, subtasks, recurrence, calendar/events, notes, habits, academics, applications, documents, search, progress, backup, and tray reminders implemented.
- [x] Recent fixes: delayed subtask parent loading, dialog focus, visible save conflicts, search editors staying closed after save, and rendered 4px scrollbars.
- [x] Recorded checks: 484 backend tests, 9 frontend tests, 22 desktop tests, disposable interface smoke, real CPU/GPU embeddings, private index integration, and backup/restore recovery. See verification records for the limits of each check.
- [x] Local v15 completed the full 1,520-row contract-v2 pass (380 steps), with 15/15 synthetic validation and 15/15 synthetic test. Earlier v14 reached 24/24 development cases and 26/30 regression cases. These are separate checks from historical v7's 83/100 qualification result. AI remains disabled and unqualified; no merged/GGUF chat export exists.
- [x] Complete the local v16 full pass over 1,592 rows (398 steps): all 1,520 replay rows plus all 72 manually reviewed public QA/abstention variants were optimized. Synthetic validation and test each passed 15/15.
- [x] Finish v16 comparisons: regression 27/30, development 24/24, clock 7/7 and public-document 2/3 retained cases (two oversized inputs excluded). Prospective v3 qualification completed at 78/100 versus base 4/100, with one flagged unsafe proposal. The release gate failed.
- [ ] Correct unrequested priorities, selected-record deadline/reminder edits, missing-time reminders, unsupported external operations, and incomplete summaries using distinct examples and replay. Freeze a fresh prospective qualification version before that informed training; v3 is now development evidence and must stay out of optimization.
- [x] Freeze prospective qualification v4 before v17: 100 newly authored cases and ten different public abstract families, hash 0899baf1318f1c4eb2f6e7f6a53e2091c29e4d0d9b474619ece89fd2199d1b78. Additional safety flags reject invented timestamps and proposed writes inconsistent with the expected user action. v3 prompts, references and source families remain outside optimization.
- [x] Finish local v17's 259-step pass over 1,036 rows and comparisons. Regression scored 27/30, development 22/24, clock 0/7 and public-document 1/3 retained cases (two oversized inputs excluded). Historical suites scored 6/12 original, 9/16 harder and 61/100 qualification. V17 failed release criteria and was not used as the v18 parent. The dataset contains 328 balanced replay rows, 36 new reviewed public examples and 672 distinct corrections/detail/alternate-envelope examples.
- [x] V17 completed all 259 training steps and all 1,036 rows. Synthetic validation and test scored 14/15 each: both valid year-boundary reminder cases were incorrectly rejected as invalid April dates. v4 qualification is running; this regression must be retained in candidate assessment.
- [x] V17 v4 qualification completed at 88/100 versus base 2/100, with one flagged unsafe proposal (task instead of requested calendar event). The release gate failed. On the identical v3 development cases it improved from v16's 78/100 to 85/100, with zero flagged unsafe outputs under the preserved v3 safety rules. Remaining comparisons continue.
- [x] Review 40 MASSIVE English training utterances and prepare 46 Folio labels, including six explicitly completed local-event variants. Preserve source revision/archive hashes and CC-BY-4.0 attribution; original calendar_set labels are not treated as Folio actions. These approvals are not yet optimized.
- [x] Freeze prospective v5 before v18: 100 new cases and ten different public abstract families; SHA-256 b9ff515b0913cbf257a267e090e7e025b6995760d3f220152cde34d3c2202993. New content rules accept semantic variants without changing earlier scores. Threshold remains 95/100 and zero unsafe proposals.
- [ ] Complete v18's full 3,348-row pass (837 steps) from v16 at 1e-5, then prospective v5 and retained regressions. Full v16/v17 data replay is preserved, with matched event/task/reminder/date contrasts, 46 reviewed MASSIVE rows and separately validated historical-schema examples. Current reminders remain remindLocal; legacy dueLocal reminder labels appear only under the two unchanged historical system contracts.
- [x] V18 training completed all 837 steps. Comparing the report's optimized IDs against the saved train split proves all 3,348 unique rows were optimized exactly once, with zero ID differences. Synthetic validation passed 15/15 and test passed 14/15. The failed reminder extracted the correct time but incorrectly included "tomorrow" in the task title. Fresh v5 qualification and retained comparisons remain pending; this is not release qualification.
- [x] V18 fresh v5 qualification completed at 83/100 versus base 1/100, with two unsafe event/reminder intent substitutions. Manual review also found a reversed quantitative result in a summary. The release gate failed; retained v4 and other regression diagnostics continue before corrective training. Preserve v5 unchanged and exclude its prompts, references and source families from any optimization.
- [x] Freeze prospective v6 before corrective training: 100 distinct cases and ten unused public abstract families, SHA-256 `767d6495b8867bd9c5b6340819269af2eb34b47a269b3769c221f54a5db1e7a6`. Each reference passes the unchanged structural/content scorer; saved train/validation/test and earlier frozen sets have no exact prompt or source-document overlap. Current release lock now requires v6, still at least 95/100 and zero unsafe proposals. No next candidate has been trained yet.
- [x] Prepare v19 corrective data: 4,508 rows, retaining all 3,348 v18 replay rows plus 840 varied task/event/reminder/edit/missing-target examples and 320 factual direction/detail/citation examples. The grammar-corrected snapshot is `target/folio-language-data-v19b`; v19's first preparation is preserved unused. Preflight proves maximum 1,434 tokens with zero oversized/truncated examples. V18 retained v4 scored 85/100 with zero unsafe and regression scored 26/30, below v16's 27/30; use v16 as the next parent.
- [ ] Complete queued v19: after V18 cycle and historical diagnostics finish, train all 4,828 rows in 1,207 steps at 2e-5 using `target/folio-language-data-v19c`, then fresh v6, retained v5/regression/development/clock/public and unchanged historical checks. The live queue never exports or deploys automatically.
- [x] Finish V18 comparison cycle: v4 85/100 (zero unsafe), regression 26/30, development 22/24, clock 7/7 and public 1/3 retained cases (two oversized cases excluded). Fresh v5 remains 83/100 with two unsafe. Development failures include a repetitive, unfinished clarification for a reversed event interval and an incomplete quoted-discussion answer. Unchanged historical suites are still running before the corrective queue starts.
- [x] Expand V19 before training to include 320 separately validated legacy-schema task, reminder, combined-Career-event, refusal, ambiguous-target, Personal-scope, abstention and injection examples. V18 original scored 7/12 and harder 9/16; the old 100-case qualification remains active. Preserve the unused v19/v19b snapshots. V19c has 4,828 rows, max 1,434 tokens and zero oversized examples; the waiting queue was replaced before any V19 optimization.
- [x] V18 historical diagnostics finished: original 7/12, harder 9/16, old qualification 74/100 with five unsafe. Its candidate remains disqualified. The corrected V19 queue verified complete weight-bound historical reports and started training from V16 with the 4,828-row v19c dataset; the full training/evaluation run is now active.
- [x] V19 completed all 1,207 optimizer steps. Independently comparing optimized IDs with the saved train split proves all 4,828 unique examples were optimized exactly once, with zero ID differences. Synthetic validation and test each passed 15/15. The eight document answers match the preserved source-reviewed reference text. Fresh v6 qualification is now running; these preliminary results do not establish release readiness.
- [x] V19 fresh v6 qualification finished at 82/100 versus base 3/100, with zero flagged unsafe proposals. One task request was refused; other failures include scientific-summary omissions, an invalid JSON LaTeX escape, and manually confirmed factual contradictions/background-contribution confusion. The release score gate failed. Retained v5 and other diagnostics continue. Preserve v6 and all its sources outside optimization.
- [x] Prepare an explicit rank-8 full-linear profile covering Qwen3 attention and MLP projections, while retaining the q/v default. Real PEFT CPU checks prove exact initial forward parity after inherited-factor transfer, nonzero added-projection gradients, complete-factor rejection, and exact FP16 merge parity. Broader-profile quality and GPU memory have not been measured; no broader candidate has been trained. Quality thresholds remain unchanged.
- [ ] After V19 diagnostics, freeze a fresh prospective set and prepare reviewed source-faithful examples emphasizing contributions versus background, measured conditions and valid JSON escaping. Measure full-linear GPU memory on the longest supported training example before committing to the full run.
- [x] V19 historical diagnostics completed at 10/12 original, 12/16 harder and 58/100 old qualification with seven unsafe outputs. The candidate remains disqualified; all reports and scores are preserved.
- [x] Freeze prospective v7 before further corrective training: 100 new cases and ten unused public abstract families, SHA-256 `208babdd1ce371dc54ea60c1253483875642e3b3fefd0424e04ba9324d409152`. References pass the scorer; saved training/evaluation prompts and document families do not overlap. Threshold stays 95/100 with zero unsafe, and earlier scores remain unchanged.
- [x] Full-linear GPU memory probe passed on the RTX 4060: all 16,515,072 adapter parameters, 504 optimizer states and eight 1,434-token microbatches across two accumulated zero-rate steps. Initial CUDA output parity was exact and all weight hashes remained unchanged. Peak allocated memory was 7,678,442,496 bytes (about 7.15 GiB). This is hardware preparation, not trained-candidate quality.
- [ ] Prepare the new source-faithful corrective dataset, train the full-linear candidate, and complete fresh v7 plus unchanged historical/export/runtime/app release gates.

The first beta targets the owner's Windows 11 x64 laptop with 16 GB RAM and an RTX 4060 with 8 GB VRAM. Wider compatibility follows later. Installer work and launcher changes necessary for stability and AI integration are now authorized. Keep models, builds, personal databases, and backups outside Git.

## 1. Improve and qualify the model

- [ ] Preserve checkpoints; train from the latest adapter on varied harder examples and successful replay examples.
- [x] Prepare and train varied date-boundary, overnight-event, invalid/missing-time, ambiguous-record, citation, abstention, summary, and document-attack examples with successful replay. Preserve each experiment and evaluate separately.
- [x] Define application contract v2 with separate deadline/reminder fields and tested backend proposal checks. Acceptance against the tuned model remains pending.
- [x] Freeze a separately authored 100-case qualification set and hashes before training. Never optimize its prompts or expected answers. Synthetic developer-authored cases are not a blinded human benchmark.
- [ ] Preserve original 12 and existing 16 regression prompts/scorers. Reach 12/12 and 16/16; require at least 95/100 qualification cases, aiming for 98/100, with no unsafe scope violations, document-induced writes, or guessed edit targets.
- [x] Reach 12/12 original and 16/16 harder development checks. Qualification v1 reached 83/100 and failed the release gate.
- [ ] Freeze a new qualification version before further training informed by v1 failure categories. Keep v1 unchanged as development evidence and exclude its prompts/answers from optimization.
- [x] Freeze prospective contract-v2 qualification v3 before the public-QA follow-up: 100 cases, including ten public abstract families excluded from training; retain v2 as the earlier pre-inference annotation snapshot. v3 SHA-256: 39994e7afb5a2b9ecd61de5ba7452d8acb2faf452aa1bbc5f2be98a87b8296cc. These are developer-reviewed checks, not human-blinded qualification.
- [ ] Require at least 95/100 v3 checks with zero unsafe scope/mode/target proposals, manually review generated answers and summaries, and preserve strict earlier regression results. A completed training pass or synthetic structured-field score does not satisfy this gate.
- [ ] Export a qualified merged model to GGUF Q4_K_M and repeat quality checks on the exact runtime/export. Scores describe finite test sets, not universal accuracy.

## 2. Windows stability

- [x] Verify cold startup, repeated reopen/close, real single-instance handling, tray survival, explicit Quit, and backend-process cleanup using a disposable workspace.
- [x] Verify actual human clicks on scheduled Windows task and event notifications open the correct editors. Both checks passed on the target laptop.
- [ ] Verify real sleep/wake catch-up and remaining reminder cancellation/duplicate cases.
- [x] Verify real native-host and parent-process termination also stop the inference child; model-runtime queue/cancellation tests pass. Chat-model lifecycle acceptance remains pending.
- [x] Exercise restore cancellation, invalid archives, successful replacement, and startup-failure rollback in disposable workspaces. Interrupted restoration and human visual review remain pending.
- [x] Automated real-launcher recovery: a checksum-valid backup with an unreadable database fails startup, rolls back to prior data, retains recovery copies, and exits without orphan backends. Human visual-dialog review and real sleep/wake remain pending.
- [x] Add stale-save/undo coverage across local records; serialize undo checks with row locks to close the later-edit race. Finish live-interface edge cases before release.
- [ ] Review keyboard focus, labels, smaller windows, and empty/error states; fix crashes, hangs, broken controls, and data-loss risks.

## 3. Integrate local AI

- [ ] Bundle pinned llama.cpp GPU/CPU runtimes and verified chat/embedding assets. No separate Ollama, Python, API key, or model installation for users.
- [ ] Connect the local AiClient and main-process authenticated inference manager. Both implementations exist with fake-runtime checks for on-demand loading, queue bounds, cancellation, fallback, idle unloading, and shutdown; real exported-model/lifecycle checks remain pending.
- [x] Wire a guarded local client/manager that activates only with verified qualified assets; ordinary builds remain disabled. Real embedding/index workflows pass; real chat workflows remain pending.
- [x] Expose local AI readiness/status and useful unavailable states; replace desktop Gemini setup/quota wording.
- [ ] Resolve targets/dates in the backend. Require explicit selection for ambiguous records, approval for every mutation, revision checks, and audit history.
- [x] Implement and test local task/event proposal guards, trusted dates/DST handling, explicit edit selection, stale revisions, cancellation, and approval-only writes with pure-Java test doubles. Real tuned-model workflows remain pending.
- [ ] Support reviewed task creation/editing, standalone events, and actual scheduled reminders; extend confirmation handling to events.
- [ ] Enable grounded answers/summaries with a pinned local embedding model. Preserve old vectors and separately identify incompatible indexes.
- [x] Implement a separate local index and validated answer/source handling; tests preserve original checksums, metadata, and legacy vectors, exclude Personal from combined search, and reject document writes/invented citations. Real pinned embeddings and private indexing pass; real tuned-chat answer/interface verification remains pending.
- [ ] Enforce explicit Personal selection; combined Academics/Career excludes Personal.
- [ ] Verify actual saved records, scheduled reminders, cited passages, abstention, indexing, cancellation, failures, restart, and integrated model quality. Core features must remain usable when AI fails.

## 4. Private beta installer

- [ ] Package final app/backend, Java, inference executables/dependencies, tuned model, embeddings, pinned asset manifest, and license notices.
- [x] Prepare pinned Temurin Java 21, runtime/model asset locks, guarded staging/build scripts, and an installer source template. Syntax checks are not a complete installer acceptance test; redistribution notices/source packaging still need final review.
- [ ] Check hashes and disk space; produce a self-contained Windows x64 offline installer with data outside its installation directory.
- [ ] Check installation without development tools; offline first launch, GPU and CPU fallback, AI workflows, reminders, backup, and restart.
- [ ] Check update and uninstall/reinstall with sample records and file checksums, preserving user data.
- [ ] Deliver the installer, SHA-256, known issues, verified hardware, and a short user testing checklist; update this checklist and evidence records.

## Completion rules

Keep completed implementation distinct from verified behavior. Automated simulations do not establish visible Windows notification behavior or real sleep/wake behavior. Do not claim an unperformed device/installation check passed. Do not ship an enabled assistant until its qualification gates pass. Public release, cloud accounts/sync, teams, external integrations, automatic updates, and claims of support for most laptops remain future work.

### V20 corrective full-linear run started

The reviewed corrective dataset contains 5,828 examples: all 4,828 V19 replay rows plus 360 public-source variants from ten new reviewed SciTLDR families (70–79), 256 source-role/formula/JSON examples, and 384 action/schema compatibility examples. Frozen evaluation families remain excluded. Token preflight found a maximum of 1,434 tokens and zero oversized rows; train SHA-256 is `b55a71f1e244e9a73406dd81a2b56a58879e084166691c4930907e56d8c35ec8`.

The full-linear rank-8/alpha-16 run is now launched from V19 at learning rate 1e-5 for 1,457 steps with accumulation 4. It uses `target/folio-source-faithful-adapter-v20`; session 19516, queue PID 19244, cycle PID 29380, training launcher PID 2424. The queue runs fresh v7 qualification, retained v6 and other comparisons, then unchanged historical suites sequentially. Check actual process state before resuming. Launch and preflight do not prove completed training or release readiness; model quality, qualified GGUF export and actual app/offline acceptance remain pending.

### Application QA in progress

The current backend suite (484), desktop unit suite (22), frontend suite (9), production build, real sandboxed Electron UI suite and launcher recovery suite passed. A delayed note-read selection race was reproduced, fixed and retained as an integration regression. See the 13 October 2026 application QA entry in VERIFICATION.md for logs and scope. Remaining asynchronous/archive/filter cases, reminder/device checks and installer acceptance continue; training remains paused.

14 October 2026: fixed archive-create/editor dismissal, stale archived-note list replacement, and reminder queue starvation after the earliest 20 records. New integration regressions and bundled-Java private HTTP checks pass; full backend suite is now 487 tests. See VERIFICATION.md for exact coverage and remaining device/installer checks. Training stays paused.

14 October 2026: reminder cancellation/rescheduling and disabled/re-enabled preference checks passed with bundled Java. Actual launcher recovery from a staged interruption preserved original records. The isolated core-only unpacked package and its packaged-resource smoke checks passed; direct executable/installer/update/uninstall and real device sleep/wake still require acceptance evidence.

14 October 2026: fixed delayed calendar list replacement and delayed event selection retargeting a new draft. Direct executable QA also found and fixed the missing packaged qualification lock and immediate-restart saved-note loss (desktop WRITE_DELAY=0). The latest unpacked package D passes direct startup, save/restart/persistence and parent-death backend cleanup in a disposable data directory. Installer/update/uninstall and real sleep/wake checks remain pending.

14 October 2026: the isolated core installer passed actual installation, versioned installer upgrade/file replacement, uninstall/reinstall, saved-record reopening and database/document checksum preservation. Its test registration was removed afterward. Direct package execution also passed with only Windows directories on the application child PATH and invalid development JAVA_HOME. Real sleep/wake and remaining broader workflow/error-state QA remain pending; network was not disabled globally.

14 October 2026: expanded real UI workflows passed habits, weighted grades, career stages, document bytes/upload failures/privacy and scoped progress. Fixed stale management controls after record creation and Ctrl+K stacking dialogs over unsaved edits. Package E and actual D-to-E installer upgrade/uninstall/reinstall passed with saved records and hashes intact. Physical Wi-Fi-off/sleep-wake checks await user timing preference; training stays paused.

14 October 2026: recurrence completion/Undo and native export cancellation/content/failure-retry checks passed. Fixed failed autosave drafts becoming inaccessible across app restarts by storing bounded, atomic workspace recovery files with stale-save protection. Package F passes the original restart failure and native restore preserves/archives draft bytes. Refreshed F installer/source binding and user-timed physical checks remain pending; training stays paused.

14 October 2026: current package F / QA installer .4 passed all automated suites (487 backend, 28 desktop, 9 frontend), actual upgrade/uninstall/reinstall and database/document/unsaved-draft checksum preservation. Identity and current acceptance are recorded in target/app-current-qa-acceptance-20261014.json. Only physical offline/sleep-wake evidence and the subsequent completion audit remain for the current core-app QA gates. AI stays disabled and training paused.

2026-10-14 device acceptance: offline test task survived relaunch with Wi-Fi off; user reports document saved. Wake reminder appeared once but click failed. Corrected Windows notification activation in package G; 33 desktop tests and runtime/editor regression pass. Human retest armed; current installer F is superseded and must be refreshed. Training remains paused.
