# Folio: Technology Stack, Architecture, Development Work, and Engineering Rationale

**A repository-based technical analysis paper**

**Prepared:** 20 October 2026

**Project:** Personal Tracker + Study RAG / Folio

**Repository examined:** the `personal-rag` working tree

**Committed baseline:** `c1791f3`, 11 October 2026, plus the current working-tree implementation and subsequent local verification records.

## Abstract

Folio is a Windows desktop workspace that combines task management, calendar planning, notes, academic tracking, habits, career applications, document storage, and an experimental retrieval-augmented assistant. Its defining architectural decision is to keep authoritative records, calculations, dates, and mutations in deterministic application code, while using a language model only for constrained interpretation and document-grounded responses. This division addresses the central problem in a personal assistant: convenient natural-language interaction must not compromise exact records or private information.

The current desktop implementation uses React and Vite for the interface, Electron for Windows integration, Java 21 and Spring Boot for application services, and file-backed H2 for local persistence. Flyway manages desktop schema changes. PDFBox, a custom Markdown parser, embedding caches, a BM25 inverted index, and reciprocal rank fusion support the RAG architecture. Earlier PostgreSQL/pgvector, Supabase, Gemini, Vercel, Render, and browser-push work remains in the repository, but it is not required for the active desktop edition. Python-based QLoRA experiments target a pinned Qwen3 4B model, with a managed llama.cpp runtime and a separate local embedding index prepared behind qualification gates.

Substantial implementation and verification work has been completed, including recurring tasks, timezone handling, note recovery, guarded undo, validated backups, installer lifecycle checks, and desktop reminders. The project remains a beta. The latest evaluated model candidate did not meet release criteria; training is paused and ordinary builds keep AI disabled. Package G contains a notification-activation correction, but human retesting and a refreshed matching installer remain outstanding. The evidence supports a developed and extensively exercised core application, not a completed AI-enabled release.

**Keywords:** local desktop application; personal knowledge management; deterministic tracking; RAG; domain isolation; React; Electron; Spring Boot; H2; QLoRA; software verification.

## 1. Purpose, scope, and method

This paper answers four questions: what technologies the project uses, what each technology contributes, what work has been completed, and why that work was necessary. It also identifies which components are active, historical, experimental, or unfinished.

The analysis is based on local source code, dependency manifests, configuration profiles, SQL migrations, tests, build scripts, project plans, Git history, and recorded acceptance evidence. It does not claim that every test was rerun for this paper. Reported test results are historical results in the inspected evidence, rather than newly measured results. No personal database or document contents were required for the analysis. Subsequent source-publication checks on 20 October are recorded separately in [VERIFICATION.md](../VERIFICATION.md#source-publication-verification--20-october-2026); the historical model/device evidence below retains its original scope.

An important distinction applies to explanations of technology choice. Where the original request or development records state a reason, this paper describes that documented rationale. Where no explicit decision record exists, it explains the engineering role supported by the implementation and labels broader conclusions as analysis. It does not reconstruct undocumented conversations or imply that alternatives were formally benchmarked.

The working tree contains modified and untracked implementation files beyond the last commit. Consequently, Git history alone understates completed local work, and local implementation must not automatically be described as committed, pushed, packaged, or released. Source presence, test evidence, and delivered-binary identity are separate claims. [S1] [S2] [S3]

## 2. The problem Folio is designed to solve

The original requirement was an all-local assistant that separates **Academics**, **Personal**, and **Career** while combining exact tracking with document retrieval. Users need both structured facts, such as a task status or assessment score, and unstructured evidence, such as a paragraph in course notes or a résumé. A conventional tracker handles the first category; a document chatbot handles the second. Folio attempts to integrate them without making the model the authority over either. [S1]

Four requirements explain much of the later engineering:

1. **Exactness:** grades, streaks, recurrence, deadlines, and record changes need repeatable rules.
2. **Privacy:** Personal data must not appear in general academic/career views or searches without explicit selection.
3. **Controlled assistance:** natural-language requests may produce proposals, but saving changes requires review and validation.
4. **Practical local use:** the installed desktop application should run without accounts, API keys, Docker, or developer tools for ordinary core workflows.

These requirements make reliability work central to the product. Losing an acknowledged note, applying an edit to the wrong task, or opening the wrong reminder target would undermine the value of the workspace even if the interface and model appeared impressive.

The resulting product is broader than its original study-RAG name. It now includes a task board, calendar, rich-text notes, habits, course grades, career stages, search, local originals, backups, and desktop lifecycle behavior. RAG remains an important development track, but the usable core is intentionally independent of it. [S2] [S3]

## 3. Development history and changes in direction

### 3.1 Exact tracker foundation

Commit `e5b61c9`, dated 29 September 2026, records the initial tracker core, domain scoping, event-log rollback, and vector storage, with 304 passing tests reported in the commit subject. This established the business rules and data boundaries before the later user interface and model integration.

The reason for this order follows directly from the original requirements: accurate records and isolation must hold even when an assistant misunderstands a request. The model is an optional input mechanism; the tracker is the source of truth. [S1]

### 3.2 Production ingestion, retrieval, and interface

Commit `a7280a2`, dated 9 October, records production RAG ingestion, the hybrid retriever, an Ollama client, REST/SSE controllers, and a web dashboard. This connected the tracker foundation to document processing and interactive use.

PDF and Markdown processing added source structure; hybrid retrieval added both semantic and exact-term matching; citations connected answers back to evidence. REST endpoints exposed operations to the interface, while server-sent events supplied an answer-delivery channel. [S7] [S8] [S10]

### 3.3 Performance work

Commit `b9164d6`, dated 10 October, records embedding caching, a BM25 inverted index, virtual-thread ingestion, and stress tests, with 423 passing tests reported. These changes target repeated embedding calls, sparse-search scanning, and serial embedding generation.

The historical request calls the cache an LRU cache. The actual Caffeine implementation identifies its eviction policy as Window TinyLFU. The accurate description is therefore a bounded, concurrent embedding cache rather than a strict LRU implementation. Likewise, the ingestion implementation extracts PDF text sequentially and parallelizes embeddings; it does not prove safe concurrent extraction from a shared PDF object. [S1] [S7] [S9]

### 3.4 Cloud implementation and desktop pivot

Commit `85052b0`, also dated 10 October, records the cloud workspace, calendar, assistant actions, and reminders. That path uses a React frontend, a Java API, Supabase authentication/database/storage, Gemini AI, and independent browser-push delivery infrastructure.

Commit `2d60008`, dated 11 October, records the local Windows Folio beta. The roadmap explicitly replaces the earlier cloud delivery plan with a local Windows edition. The subsequent commit, `c1791f3`, adds guarded local AI integration and Windows stability checks. The shift preserves much of the application while changing deployment, persistence, authentication, notification delivery, and runtime ownership. [S3] [S4] [S5]

### 3.5 Subsequent local stabilization

Local records from 13–14 October describe further training experiments and core-app QA beyond the committed baseline. Work includes editor race fixes, reminder queue corrections, durable database writes, native draft recovery, real executable testing, installer upgrades, and notification activation changes.

This history explains why the repository contains several technology generations. Their coexistence is evidence of evolving scope, rather than proof that the desktop application uses every provider and deployment platform simultaneously. [S2] [S3]

## 4. Current architecture

The desktop application has three main application layers and an optional inference subsystem:

```text
Windows user
    |
    v
React interface in a sandboxed Electron renderer
    |
    | local HTTP requests; desktop host attaches per-launch token
    v
Java 21 / Spring Boot backend on 127.0.0.1, dynamic port
    |
    +-- deterministic tracker services, validation, history, undo
    +-- H2 database and Flyway migrations
    +-- original document storage and export/backup endpoints
    +-- persistent reminder queue
    |
    +-- optional LocalAiClient
             |
             v
        authenticated desktop inference manager
             |
             v
        managed llama.cpp processes and verified model assets

Electron main process also owns tray behavior, native dialogs,
Windows notifications, recovery drafts, process lifecycle, and restore flow.
```

The main process provides privileged operating-system operations. The renderer presents records and captures user interaction. The Java backend owns domain rules, persistence, and authorization checks. This split lets the project retain the existing backend while adding Windows-specific behavior through Electron.

Desktop configuration binds the API to loopback, uses a randomly selected port, and supplies a launch-specific token. Electron injects the token only for the backend origin. This matters because a service running locally still needs protection from unrelated websites or clients that can attempt to reach localhost. [S6] [S11]

AI is an optional branch of this architecture. The ordinary desktop profile defaults to a disabled provider and disabled AI capture. Core task forms, notes, document storage, backups, and other deterministic features remain usable while model work is incomplete. [S6]

## 5. Technology stack and why each part is used

### 5.1 Java 21 and Spring Boot 3.3.5

Java 21 is the backend language and runtime target. Spring Boot 3.3.5 supplies application startup, configuration profiles, dependency injection, web endpoints, and integration with persistence and validation. Spring Web serves the REST API; Spring Data JPA maps tracker entities to relational records; Jakarta validation contributes input constraints. [S6]

The engineering fit is strong for a rule-heavy workspace: transactions and typed services support exact record changes, while `java.time` and `BigDecimal` support calendar rules and grade mathematics. Java 21 also supplies the virtual threads used for embedding concurrency. These benefits are visible in the code; the repository does not establish that Java was chosen after a formal comparison against Python, Go, or Node.

The tradeoff is a heavier packaged application than a single native executable. Bundling Java avoids asking the user to install it, but adds startup, process supervision, and distribution work.

### 5.2 Maven and the Maven wrapper

Maven defines backend dependencies, testing, and Spring Boot packaging in `pom.xml`. The wrapper scripts give the project a consistent build entry point. The produced backend JAR is staged into desktop resources.

This keeps backend builds repeatable and separates source dependencies from the Java runtime delivered to users. It does not remove the need to verify that a packaged JAR corresponds to the tested source. [S6] [S12]

### 5.3 React 19.3.0, React DOM, Vite 8.3.4, and JavaScript/JSX

The current frontend manifest declares React and React DOM 19.3.0 and Vite 8.3.4. The interface uses JavaScript and JSX components rather than a TypeScript application. Vite provides development serving and production builds. [S6]

React supports reusable workspaces for tasks, calendars, notes, documents, assistant interactions, settings, and progress. Reusing the web interface inside Electron reduces duplication between the earlier browser application and the desktop edition.

The implementation also demonstrates the cost of asynchronous interface state: late responses can replace the current view or retarget an editor. Request-generation and selection guards were added to address concrete failures. Framework choice alone does not solve those application-specific races. [S3] [S16]

### 5.4 Tiptap, ProseMirror packages, Lucide, and CSS

Tiptap packages, declared in the `^3.31.4` range, provide the rich-text notes editor, including task-list extensions and the ProseMirror dependency layer. Lucide React 1.54.0 supplies interface icons. Local CSS files implement the dark theme, workspace layouts, and responsive adjustments. [S6]

Using a structured editor supports headings, lists, checklists, links, and formatting without building editing behavior from scratch. The application still needs its own validation, safe-link handling, autosave protocol, Markdown export, and recovery semantics. These responsibilities explain why notes work extends well beyond adding an editor component.

### 5.5 Electron 44.7.0, Node.js modules, and the preload bridge

Electron 44.7.0 hosts the interface and integrates it with Windows. Its main process launches the backend, creates the window and tray, manages dialogs and notifications, supports startup preferences, and coordinates restore operations. CommonJS modules organize the desktop implementation. [S6] [S11]

The renderer uses context isolation, disabled Node integration, sandboxing, and web security. A preload bridge exposes selected desktop capabilities. Navigation and new-window restrictions constrain renderer behavior.

The reason for Electron is practical reuse of the React application together with desktop APIs. The tradeoff is additional memory and package size, plus a security boundary that must be maintained between rendered content and privileged filesystem/process operations.

### 5.6 H2, JPA/Hibernate, and direct JDBC

The desktop profile uses a file-backed H2 database in PostgreSQL compatibility mode. JPA/Hibernate supports much of the entity model; `JdbcTemplate` and explicit SQL support local features and tightly controlled queries. The database resides with workspace data rather than installed application binaries. [S6] [S13]

H2 removes the need for a database service or Docker in the desktop edition. A small connection pool suits a single local workspace. Compatibility mode helps reuse SQL, but does not establish full behavioral equivalence with PostgreSQL; profile-specific tests remain necessary.

The desktop connection sets `WRITE_DELAY=0`. This was added after direct packaged testing reproduced a saved note disappearing after immediate forced termination. The setting addresses that observed acknowledgment/durability gap; it is not a proof of survival under every physical storage or power failure. [S3] [S6]

### 5.7 Flyway desktop migrations

Flyway is enabled specifically for the desktop profile, with migrations under `db/desktop` and Hibernate set to validate the resulting schema. The inspected series includes the existing workspace baseline, local features, local history, recurrence clock fields, and a separate local AI index. [S6] [S13]

Versioned migrations make installed upgrades explicit. They help preserve identifiers, relationships, documents, history, and old embedding metadata while changing the schema. Pre-migration backups and schema validation support recovery if an upgrade fails. This is a different lifecycle from development configuration that permits automatic schema updates.

### 5.8 PostgreSQL 16 and pgvector

PostgreSQL and pgvector support the original/server-oriented vector storage path. The Java pgvector dependency is pinned to 0.1.6. `PgVectorStore` creates 768-dimensional vectors, cosine-distance retrieval, domain/document indexes, and an HNSW index. `VectorStoreConfig` selects the configured implementation, including embedded storage. [S6] [S14]

The rationale is to keep structured records and vector search within a relational system when using PostgreSQL, while retaining a local embedded option. HNSW offers an approximate retrieval strategy for that path. No comment asserting sub-millisecond performance should be treated as a measured guarantee for all datasets or hardware.

### 5.9 Apache PDFBox 3.0.3, Markdown parsing, and Tika Core 2.9.2

PDFBox 3.0.3 performs page-aware text extraction. The ingestion service implements Markdown heading parsing itself, using a heading stack in the original ingestion path. SHA-256 hashes identify unchanged file contents. Tika Core 2.9.2 is declared as a dependency, but the inspected principal PDF/Markdown paths do not justify describing Tika as their main parser. [S6] [S7]

Page and heading metadata preserve context for citations. Hashing prevents unnecessary repeated work and supports integrity checks. Original-file storage lets users retrieve the uploaded evidence independently of embeddings.

Text extraction is not OCR. The presence of PDFBox does not establish support for image-only scanned pages. The desktop local index also has a simpler heading representation than the original hierarchical parser; these two implementations should not be presented as identical.

### 5.10 Caffeine, custom BM25 indexing, and reciprocal rank fusion

Caffeine supplies a bounded, thread-safe embedding cache, configured with statistics and defensive copies of vector arrays. Cache keys include the domain and normalized text. Case is preserved to avoid altering model input semantics. [S9]

The custom inverted index maps terms to postings and supports BM25 keyword ranking. It lets sparse retrieval find exact terms across its indexed corpus rather than only rescoring candidates selected by dense retrieval. `HybridRetrieverService` fuses dense and sparse rankings with reciprocal rank fusion using `k=60`. [S8] [S9]

These technologies solve different problems: caching avoids repeated embedding work; BM25 rewards exact textual evidence; dense embeddings find semantic similarity; RRF combines ranks without requiring the two raw score scales to match.

An embedding cache is not a cache of final answers. A fast cache hit also does not imply that database search, rank fusion, inference, and rendering collectively finish within the same latency.

### 5.11 AI provider abstraction, Ollama, and Gemini

The `AiClient` interface separates application services from provider implementations. The repository includes Ollama, Gemini, local managed inference, and a deferred/disabled client. The original requirement used local Ollama models and an 8,192-token context. The later cloud path uses Gemini configuration. [S1] [S6] [S10]

This abstraction enables deployment changes without rewriting the tracker. It also creates a need for explicit embedding identity: equal vector dimensions do not make vectors from different models compatible. Current retrieval and local-index work account for model identity rather than assuming interchangeability.

Ollama is part of project history and development tooling. The intended integrated desktop package is designed to avoid a separate Ollama installation for end users. The presence of Gemini defaults in base configuration does not mean that the desktop profile sends requests to Gemini. [S2] [S6] [S15]

### 5.12 Qwen3 4B and Python-based QLoRA training

The experimental assistant uses the original-publisher `Qwen/Qwen3-4B` model at a pinned revision. The training tools use Python, CUDA-enabled PyTorch, Transformers, PEFT, Accelerate, bitsandbytes, and safetensors. The Colab requirements pin Transformers 4.57.1, PEFT 0.17.1, Accelerate 1.10.1, and bitsandbytes 0.48.1. A separate lock records the tested local environment. [S15]

QLoRA uses a 4-bit NF4 base with low-rank adapters, reducing the trainable footprint relative to updating all base weights. The early attention profile targets query/value projections with rank 8 and alpha 16; later tooling adds a seven-projection full-linear profile. Prompt tokens are masked from the loss so optimization focuses on assistant output. Gradient accumulation and checkpointing support constrained GPU memory and resumable runs.

The purpose is behavioral adaptation: structured proposals, ambiguity handling, abstention, grounding, and ignoring instructions in source documents. Frequently changing user facts belong in records and retrieved context, rather than adapter weights. End users are not expected to train the model or install Python.

### 5.13 llama.cpp, GGUF, Nomic embeddings, and a C# process host

The runtime lock pins llama.cpp revision `b11429`, with Windows x64 CPU and Vulkan artifacts and checksums. The intended chat export is GGUF Q4_K_M after qualification. A separate pinned Nomic Embed Text v1.5 GGUF uses Q8_0, 768 dimensions, explicit document/query prefixes, and an embedding-space identifier. These are configured assets and release intentions, not evidence of a qualified chat release. [S15]

A managed runtime supports lifecycle control, bounded work, cancellation, fallback, idle unloading, and shutdown. A small C# host uses Windows Job Objects to contain inference processes and terminate them when the owning process exits. It starts the child suspended, assigns it to the job, then resumes it, avoiding an uncontrolled launch interval. [S11] [S15]

The motivation is an integrated offline experience and predictable process ownership. CPU/Vulkan artifact availability does not establish acceptable chat performance across most laptops; exact exported-model and hardware checks remain necessary.

### 5.14 Packaging, Java distribution, ZIP validation, and PowerShell

Electron-builder 26.15.3 prepares Windows application resources. The manifest retains an NSIS configuration, while the explicit private-beta build script packages an unpacked application using an Inno Setup template. The actual delivery path must therefore be described from the invoked script and evidence, not inferred solely from package metadata. [S12]

A pinned Eclipse Temurin Java 21 runtime is bundled to remove a user prerequisite. Build tooling stages the backend, frontend, runtime, and optional AI assets, and checks asset identities. The `yauzl` dependency supports defensive ZIP reading for restore. PowerShell and Node scripts automate isolated installer and runtime acceptance checks.

Packaging work exists because a successful development launch is not enough: the installed application needs all runtime resources, correct paths, preserved user data, and functioning lifecycle behavior without development tools.

### 5.15 Historical cloud and browser stack

The retained cloud path includes Supabase Auth, PostgreSQL, private object storage, SQL reminder claims, Cron/Edge Functions, TypeScript/Deno delivery code, browser service-worker notifications, VAPID/Web Push, Vercel frontend configuration, Render backend configuration, and Docker files. [S5]

This path addressed remote access and reminders independent of the laptop application. The active Windows edition postpones those dependencies. Their setup instructions are historical configuration guidance, not verified statements about current service pricing, quotas, account provisioning, or deployed infrastructure.

## 6. Deterministic records and domain isolation

The data model includes tasks, habits and habit logs, courses, assessment categories and assessments, career applications, documents, and event-log entries. Desktop migrations add further local workspace structures, including notes, calendar features, history, and AI indexing. [S13]

`DomainScope` defines `ACADEMICS`, `PERSONAL`, and `CAREER`. Its general default is Academics plus Career. Null or empty explicit scope requests fail validation. Domain-aware repositories, service checks, cache keys, and retrieval filters carry that separation beyond the visible tabs. [S17]

The reason is that interface filtering alone is insufficient. If search, caching, or a record lookup ignores the selected area, a private result can leak despite correct-looking navigation. Likewise, a subtask or assessment must not silently attach to an incompatible parent simply because its identifier exists.

This is a single local workspace design. Domain isolation organizes and restricts data exposure inside that workspace; it is not database encryption or a complete operating-system access-control boundary. The public README explicitly states that Folio does not encrypt local storage. [S2]

### 6.1 Grade calculations

`GradeCalculationService` uses `BigDecimal`, explicit intermediate precision, and two-decimal display rounding. It computes category averages, applies drop-lowest rules, and weights assessed categories. Its running grade uses assessed weight rather than treating every unassessed category as a zero. [S18]

In simplified form:

```text
Running grade = sum(category average × category weight)
                / sum(weights of assessed categories)

Required final score = (target grade − already earned weighted points)
                       × 100 / remaining final weight
```

It distinguishes achieved targets, achievable required scores, and requirements above 100%. Keeping this in deterministic code makes results reproducible and testable rather than dependent on generated arithmetic.

### 6.2 Habits, recurrence, and time

Habit streaks use calendar dates and required days, including daily, weekday, weekend, and custom schedules. They do not model every day as a fixed 24-hour interval. This matters around daylight-saving transitions and for schedules with intentional gaps. [S18]

Recurring tasks create a separate successor when completed. The implementation includes missed-date skipping, short-month handling, preserved reminder offsets, stale-completion rejection, and a recurring local-clock anchor. Timed records and all-day dates have different semantics; all-day event end dates are exclusive. Nonexistent manually entered local times are rejected, while recurrence has an explicit DST-gap policy. [S3] [S13]

These are product correctness rules. They explain why time handling is distributed across backend services, migrations, frontend conversion utilities, and boundary tests rather than delegated to an LLM.

### 6.3 History and guarded undo

The event-log and rollback services record mutations and support compensating changes. Entity-specific rollback handlers account for differences among tasks, habits, documents, courses, assessments, and career applications. Desktop undo adds stale-state and related-record guards. [S19]

Undo is therefore more than restoring an old JSON snapshot. Completing a recurring task may create a successor; undo must not delete that successor after someone edits it. Row-locking and revision checks help prevent later changes being overwritten between validation and rollback. The goal is recoverability without destroying newer work. [S3]

## 7. Document processing and retrieval

### 7.1 Original ingestion pipeline

The production ingestion pipeline validates the area and document, calculates a content hash, detects unchanged contents, extracts page-aware PDF text or hierarchical Markdown sections, generates embeddings, and persists metadata and vectors. It coordinates sparse-index synchronization with transaction completion. Embeddings are generated before replacing existing vectors, reducing the risk that a failed provider call leaves an empty index. [S7]

Sequential extraction preserves predictable page ordering. Virtual threads parallelize embedding work, which can wait on an inference service or HTTP response. Asynchronous PDF ingestion returns a future and explicitly handles transaction execution. These changes target throughput and request occupancy without claiming unlimited inference capacity.

### 7.2 Hybrid ranking and citations

Dense search ranks semantic similarity. Sparse search ranks keyword evidence. RRF combines contributions according to:

```text
RRF(chunk) = sum over participating rankings of 1 / (60 + rank)
```

The original retriever carries source identifiers, page numbers, heading paths, snippet text, fusion scores, and dense/sparse provenance ranks into citations, with deterministic tie-breaking. [S8]

This combination is useful because exact course names, identifiers, and specialized terms may matter even when semantic similarity is weak. Conversely, a user may describe a concept without repeating the source wording. Citation metadata makes the retrieved evidence inspectable; it does not independently prove that every generated sentence follows from it.

### 7.3 Desktop local index

`LocalDocumentIndex` uses separate `local_ai_indexes` and `local_ai_chunks` tables keyed by document and embedding space. It checks original-file hashes, validates 768-dimensional finite nonzero vectors, normalizes embeddings, and atomically replaces the relevant model-specific index. Legacy vectors remain separate. [S13] [S20]

The implementation splits text at roughly 1,200 characters with overlap, limits documents to 512 chunks, rejects PDFs above 1,000 pages, and limits beta search to 10,000 chunks. Search constructs a sparse index from the scoped rows and performs a dense scan for the current query before rank fusion and top-five selection.

This is an important scalability distinction: the separate desktop local search rebuilds an index and scans scoped vectors during a request. Performance claims about the persistent inverted-index/PostgreSQL path cannot automatically be transferred to it. Its bounds make the current beta behavior explicit, but larger workspaces would need measurement and potentially a different strategy.

### 7.4 Storage remains independent of AI

The normal desktop profile disables automatic document indexing. Uploading and downloading originals can work with AI unavailable. This preserves the core promise that documents remain accessible without a qualified model or external provider. Experimental explicit indexing and question-answering are separately gated. [S6] [S20]

## 8. Natural-language assistance and mutation controls

The capture service uses bounded, expiring, single-use confirmation previews. A proposal is validated before it becomes a pending card; committing a card is a distinct action. The historical card lifetime is 15 minutes. Duplicate confirmation and concurrent commit attempts are guarded. [S10]

The local assistant controller resolves trusted dates and clock times in backend code, checks the selected scope, and requires an explicitly selected task with the expected revision for edits. It rejects unsupported actions, unexpected fields, wrong target IDs, and scope changes, and produces clarification responses for missing or invalid temporal information. Supported task/event proposals are handed to the confirmation flow rather than immediately executed. [S21]

The reason for these layers is that syntactically valid JSON is not necessarily an authorized or correct action. A model can return a valid timestamp that the user never requested, or a real task identifier for the wrong task. Schema validation, deterministic context resolution, selection, revision checks, and approval address different failure modes.

Retrieved document content is evidence, not an instruction source. The document-answer path validates citations and separates answering from record-writing workflows. Injection-resistance examples and abstention cases appear in training and tests, but those examples do not replace application-level restrictions. [S15] [S20]

Backend proposal tests show implemented safeguards with controlled AI outputs. They do not establish that the tuned model consistently generates useful proposals in the actual installed app. That integrated acceptance remains unfinished. [S3]

## 9. User-facing work completed and its purpose

### 9.1 Tasks and calendar

List and Board views share records, with priorities, tags, filters, sorting, statuses, archive/restore, and one level of subtasks. Recurrence handles successor creation and reminders. Calendar month, week, day, and agenda views combine deadlines, assessments, and standalone events. Drag rescheduling has equivalent editor controls. [S2] [S3]

This work turns isolated records into daily planning workflows. Explicit parent completion and bounded subtask depth keep relationships manageable; alternate controls make drag interactions less essential for keyboard users.

### 9.2 Notes and recovery

Notes support formatting, pinning, tags, archive/restore, Markdown export, autosave status, revision conflicts, and recovery drafts. A significant later correction moved failed-save recovery from browser local storage to stable workspace files. [S3] [S16]

The reason is specific: the backend chooses a new port on launch, which changes the browser origin. A draft stored under the previous origin could become inaccessible after restart. Native draft files preserve recoverability across that change. The implementation uses bounded sizes, identity validation, exclusive temporary files, fsync, atomic rename, and compare-and-remove behavior to protect newer drafts from stale responses.

Recovered edits require review and retry. Restore archives existing unsaved drafts and can reinstate them when restoration fails. Saved database records, unsaved recovery drafts, exports, and complete backups are deliberately different data products.

### 9.3 Today, progress, search, and management

Today brings together overdue and current tasks, events, assessments, and habit check-ins. Progress shows 7-day/30-day task and habit measures, grade information, and career stages. Workspace search covers records and document filenames, supports keyboard interaction, and opens the relevant editor. [S2] [S16]

This reduces fragmentation across academic, career, and personal activities. Scope-aware progress and search preserve the privacy boundary when information is aggregated.

Later corrections refreshed management controls after record creation and prevented Ctrl+K from stacking search over an unsaved editing dialog. These changes protect workflow continuity and reduce accidental loss of editing context. [S3]

### 9.4 Desktop lifecycle and reminders

The application supports a single instance, close-to-tray behavior, reopening, explicit Quit, startup preference, and backend cleanup. The reminder system stores delivery state, uses claims/leases, limits attempts, rechecks eligibility, and catches up within a bounded historical window. Resume events trigger reminder polling. [S3] [S11]

These mechanisms address application restarts, repeated polls, cancelled tasks, changed times, and hidden windows. Generic notification text reduces exposure on the lock screen. Reminder delivery still depends on an awake machine and a running tray process; Windows acceptance is different from a human seeing a notification.

The latest physical check observed one notification after wake, but clicking it failed to open Folio and the task. The resulting package G changes retain activation callbacks, use explicit toast/activation identifiers, prevent duplicate dispatch, and focus the window after loading the record. Automated activation checks pass; the actual click retest is still pending. [S3] [S22]

### 9.5 Backup, restore, and export

Complete backups include database state, original documents, and checksums. Restore validates ZIP entries, paths, counts, sizes, CRC checks, and manifest hashes before replacing active data. Staging, recovery backups, retained previous data, and a restore journal support rollback and interrupted-operation recovery. [S23]

This work makes recovery an application workflow rather than an assumption that copying a database file is sufficient. A structurally valid archive may still contain a database that cannot start; real-launcher tests exercise that failure and restoration of the previous workspace.

Structured exports serve a different purpose: records, Markdown notes, and an original-file manifest support portability and inspection. They should not be mistaken for a complete backup of every original file or unsaved recovery draft. [S3]

## 10. Concrete defects fixed and why those fixes mattered

The development evidence describes failures reproduced before fixes, which gives stronger support than a list of intended features:

- **Delayed subtask parent loading:** selected parent state could be lost while options loaded. Preserving selection prevents saving unintended hierarchy changes.
- **Late note and calendar responses:** results from an older area, filter, or selection could replace the current view. Generation guards preserve current user intent.
- **Late event selection:** an earlier lookup could turn a new unsaved event into an edit of a different record. Invalidating obsolete requests prevents wrong-target writes.
- **Save-conflict visibility:** conflicts could be obscured or dismissed poorly. Keeping drafts and displaying errors inside the relevant dialog improves recoverability.
- **Reminder starvation:** looking only at the earliest 20 records could prevent eligible later reminders from being processed. Queue selection was corrected and regression-tested.
- **Missing packaged qualification lock:** a required launcher JSON dependency was absent from the packaged app. Packaging dependency checks now cover this startup failure.
- **Acknowledged note loss after forced restart:** H2's delayed write behavior was reproduced on the actual package. Immediate write flushing corrected the observed failure.
- **Recovery drafts tied to an old origin:** native workspace drafts now survive the next backend-port change and require review before saving.
- **Stale management controls and stacked dialogs:** refresh and shortcut changes keep newly created records manageable and protect active edits.
- **Notification activation after wake:** package G corrects the identified callback/activation path, with physical acceptance still open.

Each fix addresses a causal failure, rather than adding a cosmetic feature. Collectively, they show the importance of testing packaged software, delayed responses, interruptions, and state changes beyond ordinary success paths. [S3]

One test harness also mistakenly opened the normal workspace and ran a migration. The records report byte-for-byte restoration from its pre-migration backup, preservation of recovery evidence, and a subsequent harness change that asserts the disposable directory before writes. This incident should remain part of the reliability record; it demonstrates why test isolation must be verified, not merely intended. [S3] [S22]

## 11. Model development, evaluation, and current limits

Early Qwen3 4B experiments progressed from small synthetic pilots to runtime-contract examples, boundary cases, grounded answers, abstention, and fresh qualification sets. Dataset generators, immutable snapshots, content hashes, evaluation scripts, adapter-profile checks, Colab notebooks, export gates, and runtime scoring now form a substantial developer toolchain. [S15]

The seventh experiment reached 12/12 original regressions and 16/16 harder probes, but only 83/100 on its frozen qualification set, below the release target. The earlier README emphasized those figures; they describe that historical candidate, not the newest model state. The updated README identifies V19 separately. [S2] [S15]

Later contract-v2 work reached V19. The detailed plan records a complete 1,207-step pass over 4,828 rows, 15/15 synthetic validation and test results, and **82/100 on fresh qualification v6 with zero flagged unsafe proposals**. On unchanged historical suites it scored 10/12 original, 12/16 harder, and 58/100 historical qualification, including seven unsafe proposals. Manual review also found factual errors and omitted scientific-summary details. These results demonstrate that small structured checks, new tests, historical regressions, and factual review measure different things. [S3] [S15]

V20 expands to a reviewed 5,828-example dataset and a full-linear adapter profile, with a planned 1,457-step run. The priority decision records training paused after logged step 97, with step 75 as the last saved resumable checkpoint. V20 has no completed qualification score. Launch descriptions retained later in the documents do not override that explicit pause or establish completion. [S3]

The user's 85% cutoff was used to decide whether to continue model work at that time. It did not lower the release requirement. The current qualification lock requires at least **95/100** and **zero unsafe proposals**, together with other required regressions, factual review, exact export/runtime checks, and integrated application acceptance. No qualified merged/GGUF chat export is established by the inspected records. [S3] [S15]

The reason for frozen, prospective evaluation sets is to limit overfitting to known examples. Once failures influence a new training pass, those inspected tests are useful regressions, but no longer sufficient independent evidence. A lower training loss, a completed run, or zero unsafe outputs in one finite set cannot establish universal correctness.

## 12. Verification evidence and what it establishes

The verification approach spans backend unit and integration tests, domain boundaries, concurrency checks, adversarial cases, frontend time/calendar tests, desktop filesystem/runtime tests, real Electron UI smoke, real packaged executables, and isolated installer operations. Some tests use deterministic stubs; others use real bundled Java, embeddings, files, or Windows processes. Those levels should remain distinguishable. [S3]

The latest inspected acceptance JSON records **487 backend tests, 33 desktop tests, and 9 frontend tests**. Earlier package F acceptance recorded 28 desktop tests; notification work raised that desktop figure. These are recorded counts from related acceptance runs, not a claim that a fresh combined 529-test suite was executed while writing this paper. [S22]

Evidence includes actual note/document saves and reopening, UTF-8 original-byte preservation, wrong-scope exclusion, recurrence completion and undo, native export cancellation and failed-write retry, restore rollback, process cleanup, and installer upgrade/uninstall/reinstall with checksum preservation. A restricted child environment removed development tools from PATH, providing evidence that the app used bundled resources. [S3]

Two limitations are particularly important:

1. **Package identity matters.** Installer acceptance applies to the package actually installed. The latest acceptance record identifies package G source binding but an older package F installer, and explicitly marks `installer_matches_current_source` false.
2. **Physical behavior needs physical evidence.** Human observations support an offline task save/reopen and a document save, but offline note reopening, document reopening, and document-download integrity were not all recorded. Wake notification visibility was observed; click activation failed before the fix and awaits retest afterward.

Some top-level booleans still say physical checks are false while nested records contain partial observations. The correct reading is incomplete acceptance with recorded partial results, not either “no physical observation occurred” or “all physical checks passed.” [S22]

Performance benchmarks and original latency targets also need context. Stubbed embedding delays and in-memory datasets can demonstrate algorithmic improvements without measuring actual GPU inference, cold startup, disk behavior, or deployed end-to-end latency. This paper therefore does not repeat requested latency or throughput thresholds as universal achieved figures. [S1] [S3]

## 13. Architectural assessment and tradeoffs

### 13.1 Strongest design choices

**Separating deterministic authority from generated assistance** is the most important choice. The product remains useful with AI disabled, and wrong model outputs can be rejected before affecting records.

**Preserving original evidence** supports file recovery, citation inspection, and re-indexing. Embeddings are derived representations, not replacements for source files.

**Carrying scope into the backend and retrieval layers** makes privacy part of the data contract rather than just a navigation convention.

**Treating recovery as a feature** is supported by checksum validation, staged restoration, native draft storage, stale-save checks, and packaged restart testing.

**Binding tests to delivered resources** addresses a common gap between passing source tests and a functioning installed application. The missing lock file and durable-write defect were found at this layer. [S3]

### 13.2 Costs and unresolved concerns

The combination of React, Electron, Java, H2, Python training, llama.cpp, and a C# process host creates several build and runtime boundaries. This is understandable given code reuse and local delivery goals, but increases packaging, debugging, license review, process cleanup, and compatibility work.

Historical and active paths coexist: cloud versus desktop authentication, old versus local model-specific indexes, multiple provider implementations, and NSIS configuration alongside Inno build scripts. This flexibility also raises maintenance cost and makes documentation precedence important.

Several source files are densely formatted. As an analysis finding, that increases the effort needed to inspect validation and transactional behavior. It is a maintainability concern, not evidence that those files are functionally incorrect.

Local storage remains unencrypted by Folio. The single-workspace desktop model and per-launch API token do not provide multi-tenant service isolation. The bounded local search implementation has a different scaling profile from pgvector/HNSW. AI quality, exported-model fidelity, memory use, CPU fallback experience, and wider Windows hardware support remain insufficiently established for broad release claims. [S2] [S3] [S20]

## 14. Remaining work and the reason for each gate

The most immediate core-app work is to complete the human notification click retest on the corrected package, finish the partially recorded offline/device checks, generate a matching installer, and bind the final acceptance record to that exact build. These gates close specific evidence gaps rather than requiring a new feature rebuild. [S3] [S22]

Broader keyboard, focus, labels, narrow-window, empty-state, and error-state review remains in the plan. It matters because an implementation can satisfy backend rules while still blocking a user from completing a workflow.

Model work remains paused. When resumed by the owner, the preserved checkpoint and optimizer state provide continuity, but the next candidate still needs independent quality evaluation, historical regression checks, factual review, and unsafe-proposal checks. A qualified adapter must then be exported and tested on the exact GGUF/runtime pairing; adapter scores cannot be assumed to survive merge and quantization unchanged. [S3] [S15]

The intended AI-enabled installer also requires verified runtime/model assets, license notices and redistribution review, disk/hash checks, real CPU/GPU lifecycle acceptance, actual assistant proposals and document answers, and installation testing without developer prerequisites. Only those results can establish the integrated experience promised by the design.

Cloud sync, accounts for the desktop edition, teams, shared workspaces, external calendar integrations, automatic updates, and broad hardware support remain deferred. Their presence in earlier ideas or retained files does not make them current completed features. [S3] [S4]

## 15. Conclusion

Folio has evolved from a scoped tracker and study-RAG backend into a substantial local Windows productivity application. Its stack reflects that evolution: Java and Spring Boot preserve deterministic rules and transactions; React provides a reusable workspace interface; Electron supplies desktop integration; H2 and Flyway make local persistence practical; PDFBox, BM25, embeddings, and rank fusion support document retrieval; and a separately gated Python/llama.cpp toolchain explores on-device assistance.

The work completed is significant in both breadth and depth. Beyond visible features, the project contains fixes for wrong-target edits, stale responses, saved-data durability, cross-restart drafts, unsafe restoration, packaged dependencies, reminder scheduling, and process lifecycle. The reasons are consistent: authoritative personal records need predictable behavior, recoverability, and controlled access.

The supported status is a developed desktop beta with substantial recorded core-app verification and an unfinished AI release track. The final notification/device acceptance and installer identity need closure, and the assistant remains unqualified. Maintaining those distinctions is essential to an accurate account of what has been done and why.

## Appendix A. Repository guide

- `src/main/java/com/tracker/rag/controller`: REST/SSE endpoints and desktop-specific API flows.
- `src/main/java/com/tracker/rag/service`: tracker rules, ingestion, retrieval, AI clients, local features, history, and rollback.
- `src/main/java/com/tracker/rag/model` and `repository`: relational entities and persistence interfaces, including vector stores.
- `src/main/resources`: profile configuration, desktop migrations, AI contract, and the retained earlier static interface.
- `frontend/src`: React workspaces, editor components, API interaction, calendar/time utilities, and styles.
- `desktop`: Electron host, preload bridge, runtime control, backups, drafts, notifications, inference management, packaging, and acceptance scripts.
- `training`: dataset builders, model downloads, adapter training, evaluation, release qualification, export, and Colab tooling.
- `supabase`: retained cloud schema, reminder SQL, Edge Function, and delivery tests.
- `docs`: sample-data screenshots, device-check procedure, and this paper.
- `target`: ignored generated builds and local evidence. Referenced local reports are not guaranteed to accompany a fresh clone.

## Appendix B. Evidence and source register

References below point to primary project sources. Versions are the versions declared or locked in this repository, not claims about the latest upstream releases. Runtime defaults, requested capabilities, implemented code, historical test results, and accepted releases are distinct forms of evidence.

[S1]: <../ORIGINAL_REQUEST.md>
[S2]: <../README.md>
[S3]: <../VERIFICATION.md>
[S4]: <../LOCAL_ROADMAP.md>
[S5]: <../DEPLOYMENT.md>
[S6]: <../pom.xml>
[S7]: <../src/main/java/com/tracker/rag/service/ingestion/DocumentIngestionService.java>
[S8]: <../src/main/java/com/tracker/rag/service/HybridRetrieverService.java>
[S9]: <../src/main/java/com/tracker/rag/service/cache/CaffeineEmbeddingCache.java>
[S10]: <../src/main/java/com/tracker/rag/service/CaptureService.java>
[S11]: <../desktop/main.cjs>
[S12]: <../desktop/scripts/build-beta.cjs>
[S13]: <../src/main/resources/db/desktop/V5__local_ai_index.sql>
[S14]: <../src/main/java/com/tracker/rag/repository/vector/PgVectorStore.java>
[S15]: <../training/colab/README.md>
[S16]: <../frontend/src/main.jsx>
[S17]: <../src/main/java/com/tracker/rag/domain/DomainScope.java>
[S18]: <../src/main/java/com/tracker/rag/service/GradeCalculationService.java>
[S19]: <../src/main/java/com/tracker/rag/service/RollbackService.java>
[S20]: <../src/main/java/com/tracker/rag/service/desktop/LocalDocumentIndex.java>
[S21]: <../src/main/java/com/tracker/rag/controller/LocalAssistantActionController.java>
[S22]: <../VERIFICATION.md#windows-notification-click-regression-2026-10-14>
[S23]: <../desktop/backup.cjs>

- **S1 — Original requirements and milestone requests:** [ORIGINAL_REQUEST.md](<../ORIGINAL_REQUEST.md>).
- **S2 — Public feature overview and historical model summary:** [README.md](<../README.md>).
- **S3 — Detailed test, defect, model, packaging, and device evidence:** [VERIFICATION.md](<../VERIFICATION.md>). Current priority, training pause, and release rules: [PLAN.md](<../PLAN.md>).
- **S4 — Desktop scope and superseded roadmap context:** [LOCAL_ROADMAP.md](<../LOCAL_ROADMAP.md>), [DESKTOP.md](<../DESKTOP.md>).
- **S5 — Historical cloud implementation:** [DEPLOYMENT.md](<../DEPLOYMENT.md>), [cloud reminder design](<../supabase/REMINDERS.md>).
- **S6 — Dependency/configuration evidence:** [pom.xml](<../pom.xml>), [frontend/package.json](<../frontend/package.json>), [desktop/package.json](<../desktop/package.json>), [desktop profile](<../src/main/resources/application-desktop.properties>), [cloud profile](<../src/main/resources/application-cloud.properties>).
- **S7 — Main ingestion implementation:** [DocumentIngestionService.java](<../src/main/java/com/tracker/rag/service/ingestion/DocumentIngestionService.java>).
- **S8 — Hybrid rank fusion:** [HybridRetrieverService.java](<../src/main/java/com/tracker/rag/service/HybridRetrieverService.java>).
- **S9 — Caching and sparse retrieval:** [CaffeineEmbeddingCache.java](<../src/main/java/com/tracker/rag/service/cache/CaffeineEmbeddingCache.java>), [EmbeddingCacheKey.java](<../src/main/java/com/tracker/rag/service/cache/EmbeddingCacheKey.java>), [InvertedIndexServiceImpl.java](<../src/main/java/com/tracker/rag/service/retrieval/InvertedIndexServiceImpl.java>).
- **S10 — Capture and answer delivery:** [CaptureService.java](<../src/main/java/com/tracker/rag/service/CaptureService.java>), [ChatController.java](<../src/main/java/com/tracker/rag/controller/ChatController.java>).
- **S11 — Desktop trust boundary and process ownership:** [main.cjs](<../desktop/main.cjs>), [preload.cjs](<../desktop/preload.cjs>), [DesktopApiGuard.java](<../src/main/java/com/tracker/rag/config/DesktopApiGuard.java>), [InferenceHost.cs](<../desktop/native/InferenceHost.cs>).
- **S12 — Packaging:** [build-beta.cjs](<../desktop/scripts/build-beta.cjs>), [folio.iss](<../desktop/installer/folio.iss>), [Java runtime lock](<../desktop/java-runtime-lock.json>).
- **S13 — Desktop database migrations:** [V1](<../src/main/resources/db/desktop/V1__existing_workspace.sql>), [V2](<../src/main/resources/db/desktop/V2__local_features.sql>), [V3](<../src/main/resources/db/desktop/V3__local_history.sql>), [V4](<../src/main/resources/db/desktop/V4__recurrence_clock.sql>), [V5](<../src/main/resources/db/desktop/V5__local_ai_index.sql>).
- **S14 — Vector storage:** [PgVectorStore.java](<../src/main/java/com/tracker/rag/repository/vector/PgVectorStore.java>), [VectorStoreConfig.java](<../src/main/java/com/tracker/rag/config/VectorStoreConfig.java>).
- **S15 — Training and managed AI evidence:** [training README](<../training/README.md>), [contract-v2 README](<../training/colab/README.md>), [train.py](<../training/colab/train.py>), [LOCAL_AI.md](<../LOCAL_AI.md>), [base lock](<../desktop/ai/base-lock.json>), [runtime lock](<../desktop/ai/runtime-lock.json>), [embedding lock](<../desktop/ai/embedding-lock.json>), [qualification lock](<../desktop/ai/release-qualification-lock.json>).
- **S16 — Interface implementation:** [main.jsx](<../frontend/src/main.jsx>), [NotesWorkspace.jsx](<../frontend/src/NotesWorkspace.jsx>), [CalendarWorkspace.jsx](<../frontend/src/CalendarWorkspace.jsx>), [RecordManager.jsx](<../frontend/src/RecordManager.jsx>).
- **S17 — Domain contract:** [DomainScope.java](<../src/main/java/com/tracker/rag/domain/DomainScope.java>).
- **S18 — Deterministic calculations:** [GradeCalculationService.java](<../src/main/java/com/tracker/rag/service/GradeCalculationService.java>), [HabitStreakService.java](<../src/main/java/com/tracker/rag/service/HabitStreakService.java>).
- **S19 — History and rollback:** [EventLogService.java](<../src/main/java/com/tracker/rag/service/EventLogService.java>), [RollbackService.java](<../src/main/java/com/tracker/rag/service/RollbackService.java>).
- **S20 — Separate desktop retrieval/index:** [LocalDocumentIndex.java](<../src/main/java/com/tracker/rag/service/desktop/LocalDocumentIndex.java>), [LocalDocumentAiController.java](<../src/main/java/com/tracker/rag/controller/LocalDocumentAiController.java>).
- **S21 — Local proposal validation:** [LocalAssistantActionController.java](<../src/main/java/com/tracker/rag/controller/LocalAssistantActionController.java>), [LocalActionTimes.java](<../src/main/java/com/tracker/rag/service/desktop/LocalActionTimes.java>).
- **S22 — Latest local acceptance and physical observations:** [published verification narrative](../VERIFICATION.md#windows-notification-click-regression-2026-10-14). The detailed `target/app-current-qa-acceptance-20261014.json` and `target/app-device-check-session-20261014.json` reports are ignored local artifacts and do not accompany a fresh clone.
- **S23 — Recovery and notifications:** [backup.cjs](<../desktop/backup.cjs>), [note-drafts.cjs](<../desktop/note-drafts.cjs>), [notifications.cjs](<../desktop/notifications.cjs>), [LocalReminderService.java](<../src/main/java/com/tracker/rag/service/desktop/LocalReminderService.java>).
