# Folio — current implementation plan

Updated 11 October 2026. This is the main task checklist. Detailed evidence lives in [VERIFICATION.md](VERIFICATION.md) and [LOCAL_AI.md](LOCAL_AI.md); [LOCAL_ROADMAP.md](LOCAL_ROADMAP.md) describes the earlier scope.

## Current position

- [x] Local Windows workspace: tasks, board, subtasks, recurrence, calendar/events, notes, habits, academics, applications, documents, search, progress, backup, and tray reminders implemented.
- [x] Recent fixes: delayed subtask parent loading, dialog focus, visible save conflicts, search editors staying closed after save, and rendered 4px scrollbars.
- [x] Recorded checks: 484 backend tests, 9 frontend tests, 22 desktop tests, disposable interface smoke, real CPU/GPU embeddings, private index integration, and backup/restore recovery. See verification records for the limits of each check.
- [x] Seven Qwen3 4B adapter experiments completed. Latest: 12/12 original regression checks, 16/16 harder probes, and 83/100 frozen qualification cases with no flagged unsafe outputs. AI remains disabled and unqualified; no merged/GGUF chat export exists.

The first beta targets the owner's Windows 11 x64 laptop with 16 GB RAM and an RTX 4060 with 8 GB VRAM. Wider compatibility follows later. Installer work and launcher changes necessary for stability and AI integration are now authorized. Keep models, builds, personal databases, and backups outside Git.

## 1. Improve and qualify the model

- [ ] Preserve checkpoints; train from the latest adapter on varied harder examples and successful replay examples.
- [x] Prepare and train varied date-boundary, overnight-event, invalid/missing-time, ambiguous-record, citation, abstention, summary, and document-attack examples with successful replay. Preserve each experiment and evaluate separately.
- [x] Define application contract v2 with separate deadline/reminder fields and tested backend proposal checks. Acceptance against the tuned model remains pending.
- [x] Freeze a separately authored 100-case qualification set and hashes before training. Never optimize its prompts or expected answers. Synthetic developer-authored cases are not a blinded human benchmark.
- [ ] Preserve original 12 and existing 16 regression prompts/scorers. Reach 12/12 and 16/16; require at least 95/100 qualification cases, aiming for 98/100, with no unsafe scope violations, document-induced writes, or guessed edit targets.
- [x] Reach 12/12 original and 16/16 harder development checks. Qualification v1 reached 83/100 and failed the release gate.
- [ ] Freeze a new qualification version before further training informed by v1 failure categories. Keep v1 unchanged as development evidence and exclude its prompts/answers from optimization.
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
