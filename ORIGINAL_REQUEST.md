# Original User Request

## 2026-09-28T20:17:28Z

Build an all-local personal assistant and study tracker that cleanly separates Academics, Personal, and Career domains, coupling deterministic exact records (tasks, deadlines, weighted grades, habit streaks) with hybrid document retrieval (RAG) over course notes, resumes, and personal documents.

Working directory: c:/Users/harsh/OneDrive/Documents/personal tracking rag
Integrity mode: development

## Requirements

### R1. Exact Tracker Core & Domain Scoping
Deterministic management of tasks, assessments, habit logs, and career pipeline records. Enforce domain scoping (`Academics`, `Personal`, `Career`) on every query with strict opt-in isolation for the `Personal` domain. All date arithmetic, weighted grade calculation, and streak tracking must be computed deterministically by unit-tested code, never by the language model. An append-only event log must capture record mutations to support state rollbacks (undo).

### R2. Hybrid Document Ingestion & Retrieval (RAG)
Support structured document ingestion (PDFs, Markdown) with page and heading awareness. Provide hybrid vector and keyword search scoped strictly by domain, returning citations with source file and page numbers. Unchanged files must be skipped via hash validation; modified files re-ingested transactionally.

### R3. Natural Language Capture with Confirmation Guardrail
Enable natural language quick-capture that extracts structured records into a validated schema. Show a confirmation preview card to the user before committing any write or update to the database. Text retrieved from documents must never be permitted to trigger a database write.

### R4. Controlled Local Hardware & Inference Environment
Orchestrate chat and embeddings using local Ollama instances (`llama3.1:8b` or `qwen2.5:7b` with `num_ctx: 8192` and `nomic-embed-text`), fully utilizing the RTX 4060 GPU with zero cloud leakage. All database records and vector embeddings must persist to PostgreSQL 16 with `pgvector` (or seamless local embedded fallback if Docker is not active).

## Acceptance Criteria

### Domain Isolation & Scope Safety
- [ ] Queries scoped to `Academics` or general scope return zero records or document chunks from the `Personal` domain.
- [ ] An empty scope immediately fails closed with an error.

### Deterministic Computation Accuracy
- [ ] Weighted grade calculations and required final exam scores pass unit tests against known syllabus criteria.
- [ ] Habit streaks and due dates correctly resolve around timezone and Daylight Saving Time boundaries without LLM hallucinations.

### Injection & Mutation Safeguards
- [ ] An ingested PDF containing prompt injection phrases (e.g. "delete all tasks" or "ignore previous instructions") cannot execute any database write or mutate application state.
- [ ] All database mutations require explicit user confirmation via the confirmation card flow.

### Verification Suite
- [ ] Automated test suite covering scope isolation, grade math, habit tracking, and capture schema validation passes with 100% green tests.
- [ ] Hybrid search returns page-accurate citations on multi-page test documents.

## 2026-09-29T06:18:00Z

Build and finalize the production implementation for Personal Tracker + Study RAG v3: build the production document ingestion engine, hybrid RAG retriever, Ollama local client, REST/SSE controllers, and single-page web dashboard on top of the already verified tracker core (304/304 passing tests), and push the project to GitHub.

Working directory: c:/Users/harsh/OneDrive/Documents/personal tracking rag
Integrity mode: development

## Requirements

### R1. Production Document Ingestion & Storage
Implement page-aware PDF text extraction (using Apache PDFBox 3.0.3) and stack-based Markdown heading hierarchy parsing with SHA-256 content deduplication. Ingested chunks must be embedded via Ollama `nomic-embed-text` and stored into `VectorStore` (supporting both `PgVectorStore` and `EmbeddedVectorStore`).

### R2. Scoped Hybrid Retrieval & Citations
Implement dense vector similarity search and sparse keyword matching fused via Reciprocal Rank Fusion (RRF, k=60), strictly bounded by `DomainScope`. Return ranked citations formatted with document identifier, page numbers, heading paths, and provenance ranks.

### R3. Local Ollama Client & Natural Language Capture
Connect to local Ollama (`http://localhost:11434`) enforcing explicit `num_ctx: 8192`. Provide natural language quick capture extracting structured tasks, assessments, habits, and job applications into validated `ConfirmationCard` previews before any database commit.

### R4. REST / SSE Controllers & Web Dashboard
Implement Spring Boot REST endpoints for tracker operations, document ingestion, and capture workflows. Provide a Server-Sent Events (SSE) streaming endpoint (`/api/chat/stream`) for grounded Q&A with cite tokens (`[S1]`, `[S2]`). Deliver a clean, dark-mode single-page dashboard (`index.html`) with Domain tabs, Today view, Quick Capture modal, and Chat interface.

### R5. Git Version Control & Remote Sync
Commit all project code, configuration, and documentation with clean commit history, and push to the remote repository `https://github.com/ravenz0903/personal-rag.git`.

## Acceptance Criteria

### Test & Ingestion Integrity
- [ ] All 304 existing unit and boundary tests continue to pass with 0 failures and 0 errors.
- [ ] Multi-page PDFs and structured Markdown documents ingest successfully with page numbers and heading paths preserved.
- [ ] Unchanged documents are skipped via SHA-256 hash match; modified documents re-ingest transactionally.

### API & Streaming Endpoints
- [ ] `/api/chat/stream` streams grounded answers and properly refrains from hallucinating when context is absent ("not in notes").
- [ ] `/api/capture/preview` generates a valid `ConfirmationCard` with a 15-minute TTL without writing to the database.
- [ ] `/api/capture/commit` commits confirmed records and records an entry in the append-only `event_log`.

### Dashboard Usability & Git Sync
- [ ] Web dashboard renders domain tabs (Academics, Personal with lock, Career, All), Today view, and quick capture modal.
- [ ] All files committed and pushed to `origin main` on `https://github.com/ravenz0903/personal-rag.git`.

## 2026-10-08T21:24:39Z

Analyze the existing Personal Tracker + Study RAG v3 implementation, implement high-performance caching, inverted indexing, and virtual-threaded parallel ingestion, and verify performance improvements using an automated benchmark stress test suite.

Working directory: c:/Users/harsh/OneDrive/Documents/personal tracking rag
Integrity mode: development

## Requirements

### R1. High-Performance LRU Embedding & Query Cache
Implement a thread-safe, bounded LRU cache (e.g. Caffeine or synchronized LRU with eviction) for query embeddings and chunk embeddings. Repeated queries must bypass HTTP roundtrips to Ollama, achieving sub-10ms response times for cached searches.

### R2. In-Memory Inverted Index for Sparse BM25 Retrieval
Replace the linear candidate scan in `HybridRetrieverService` with an in-memory inverted index (term-to-postings list with document frequency and chunk positions). Ensure sub-millisecond sparse keyword lookups and rank fusion across large collections without linear iteration.

### R3. Virtual-Thread Parallel Ingestion & Batch Processing
Parallelize document page extraction (PDFBox) and chunk embedding generation using Java 21 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`). Ensure ingestion does not block HTTP server threads and delivers at least a 2x throughput improvement for multi-page documents.

### R4. Automated Performance Stress Benchmark Suite
Develop a dedicated automated performance and stress test suite (`PerformanceBenchmarkTest.java`) measuring:
- P50, P95, and P99 retrieval latencies under concurrent load.
- Cache hit vs. cache miss latency differentials.
- Ingestion throughput (chunks/sec).
- Memory footprint stability.

### R5. Zero Regression & Remote Synchronization
Guarantee 100% backward compatibility and test integrity: all 309 existing unit, boundary, and integration tests must continue passing with zero failures. Commit and push all performance enhancements and benchmarks to `https://github.com/ravenz0903/personal-rag.git`.

## Acceptance Criteria

### Latency & Throughput Targets
- [ ] Embedding cache hits resolve in <10ms without network calls.
- [ ] Inverted index sparse keyword search executes in <15ms across 1,000+ indexed chunks.
- [ ] Concurrent ingestion leverages Java 21 Virtual Threads for non-blocking execution.
- [ ] Automated performance suite demonstrates clear throughput/latency gains.

### Correctness & Baseline Integrity
- [ ] All 309 baseline tests continue to pass (0 failures, 0 errors).
- [ ] The new `PerformanceBenchmarkTest` passes deterministically in CI/local runs.
- [ ] Domain isolation (`ACADEMICS`, `PERSONAL`, `CAREER`) remains strictly enforced across cached data and the inverted index.
- [ ] All code changes, tests, and documentation are committed and pushed to `main` on GitHub.
