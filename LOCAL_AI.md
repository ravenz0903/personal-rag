# Folio managed local AI — evaluation and packaging work

Updated 11 October 2026. This is work in progress: no model is yet enabled in the app, no bundled AI installer has been built, and a universal hardware claim has not been established.

## Product decision

Users should install Folio once. The offline distribution must include the app, Java runtime, inference executable, one approved chat model, and one approved embedding model. No separate Ollama installation, account, API key, terminal command, or model picker is required for ordinary use. Keep the core usable if AI cannot load. The latest approved plan authorizes a private beta installer and necessary launcher work after quality, stability, and integration gates; see [PLAN.md](PLAN.md).

Use a Folio-managed `llama.cpp` process as the packaging candidate. It supports CPU inference and GPU backends and can serve a private local API. Existing Ollama is used only for exploratory model evaluation; it is not a user installation requirement. A successful Ollama test does not verify the shipped llama.cpp build or quantization.

## Candidate shortlist, not a final ranking

| Candidate | Purpose | Position |
|---|---|---|
| Qwen3.5 2B, text-only Q4_K_M | General answers, summaries, action proposals | First default candidate: smaller than 4B, but must pass grounded-answer and action tests |
| Qwen3.5 0.8B, text-only quantized | Lower-memory comparison | Test whether size savings are worth the quality loss; do not assume reliable actions |
| Qwen2.5 1.5B Instruct Q4_K_M | Older text-only compatibility baseline | About 986 MB in Ollama; an original-publisher GGUF exists. Compare against the newer small models rather than assuming newest is best |
| Qwen3 4B / Qwen3.5 4B | Quality comparison or later optional upgrade | More memory; not the universal default merely because the development laptop has a GPU |
| Nomic Embed Text | Local document retrieval | Compact baseline already present in the development environment; compare retrieval quality before changing existing embedding spaces |
| Qwen3-Embedding 0.6B | Retrieval comparison | Larger candidate; evaluate whether quality justifies additional installed size and memory |

The published Unsloth Qwen3.5 2B Q4_K_M text weights are about 1.28 GB. Its separate vision projector is about 671 MB. Omit vision from the first text-assistant candidate; image understanding can be a later explicit addition. These are file sizes, not runtime RAM requirements. A complete Folio package also needs the app/runtime, embedding weights, license notices, and installation/update staging space. Measure exact shipped bytes after selecting pinned artifacts.

Sources: [Qwen original 2B model](https://huggingface.co/Qwen/Qwen3.5-2B), [quantized 2B artifacts](https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/tree/main), [runtime](https://github.com/ggml-org/llama.cpp), [server controls](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md), [embedding baseline](https://ollama.com/library/nomic-embed-text).

## Compatibility target and gates

- Target Windows x64 with 8 GB RAM and no dedicated GPU first; this is a test target, not a confirmed minimum. Treat Windows ARM64 as a separate future runtime/build target.
- Start with 2,048–4,096 context tokens, one active request, bounded output, and retrieved passages rather than whole documents.
- Measure cold start, warm response, peak combined Folio/model memory, tokens per second, cancellation, and out-of-memory recovery.
- Test CPU-only on the development machine, then actual lower-end laptops. Forcing CPU on a fast development processor does not simulate an older laptop.
- Provide a compatible CPU build independent of a GPU runtime. Detect backend support and try a tested GPU backend; fall back to CPU on load failure. CPU instruction compatibility must be verified for the exact binary; x64 alone is insufficient.
- Unload the model after inactivity. Tray reminders and normal forms must not require the model to be resident.
- Do not claim “runs on most laptops” until representative 8 GB integrated-graphics, 16 GB integrated-graphics, and dedicated-GPU machines have passed. Slower machines may need AI disabled while the core remains functional.

## Integrated setup and runtime contract

1. Build-time manifest pins runtime version, architecture/backend, model repository revision, quantization, byte sizes, and SHA-256 values. Never ship a floating `latest` model.
2. Include all required local assets and applicable license/notice files in the offline package. Qwen's published Apache-2.0 and llama.cpp's MIT licenses are candidate inputs; inspect the exact runtime dependencies and embedding license before redistribution.
3. Show one Folio setup with disk-space checks. No first-run model download in the complete offline edition. Use the chosen installation directory for immutable weights; user records remain outside it.
4. Validate assets before launch; show a useful AI-unavailable state on missing/corrupt files without blocking core features. Updates must not delete records or replace weights mid-request.
5. Launch only Folio's packaged executable with fixed arguments, an ephemeral loopback port, a random per-launch API key, disabled web UI, and no built-in agent/external tools. The Java backend mediates requests; renderer code does not receive the model service key.
6. Folio owns lifecycle, cancellation, bounded queue, idle unload, shutdown, logs, and crash recovery. Test that no orphan model process remains after Quit or host failure.
7. Treat model output as untrusted: validate fields, scope, dates, existing record IDs, and revisions before producing an approval card. No direct model database writes, shell tools, or automatic destructive actions.
   Separate document-answer mode from action mode. The former cannot propose writes at all. Resolve edit targets with application search; when there are zero or multiple exact matches, require record selection/clarification rather than allowing the model to guess a valid ID. Approval alone is not sufficient if the proposal targets the wrong record. Use action-specific schemas rather than one schema containing every optional field.
8. Keep old vectors intact. An embedding change creates a separate index with model/quantization/preprocessing metadata; enable it only after retrieval checks pass. Do not mix incompatible vectors.

## Evaluation started

Completed exploratory CPU comparisons on the development laptop (4,096-token context, short synthetic prompts, temperature 0):

| Candidate / tested quantization | Strict checks passed | Median request time | Median generation speed | Maximum runtime-reported allocation |
|---|---:|---:|---:|---:|
| Qwen2.5 1.5B Q4_K_M | 3 / 12 | 8.13 seconds | 22.24 tokens/second | 1.09 GiB |
| Qwen3.5 2B Q8_0 | 5 / 12 | 17.02 seconds | 10.73 tokens/second | 2.20 GiB |
| Qwen3 4B Q4_K_M | 6 / 12 | 11.94 seconds | 8.41 tokens/second | 2.97 GiB |

These are single runs on a modern processor with GPU offload explicitly disabled. Allocation is Ollama's reported model allocation, not measured peak combined Windows/Folio memory. Date/title-format differences contributed to failures; semantic review and validated normalization are separate work. All three candidates also failed ambiguity or document-injection checks. None is approved for integration. The Q8 2B measurement must not be advertised as Q4 performance. Scores are too narrow to declare a general winner. Qwen3 4B is the stronger measured small-model baseline in this particular run; text-only Qwen3.5 2B Q4 remains the lower-memory packaging candidate to evaluate next.

The test models were downloaded into the existing development Ollama installation for evaluation. These downloads are not part of a finished Folio installation. Production packaging still needs pinned GGUF assets and the managed runtime; no separate Ollama installation will be required by the intended offline distribution.

## GPU comparison and training pilot

The same 12 cases were repeated with automatic GPU offload on the development RTX 4060 laptop. Runtime-reported VRAM accounted for essentially all model allocation. GPU changes speed, not the qualification gate:

| Candidate | Strict checks passed | Median request time | Median generation speed | Maximum runtime-reported VRAM |
|---|---:|---:|---:|---:|
| Qwen2.5 1.5B Q4_K_M | 3 / 12 | 1.25 seconds | 154.39 tokens/second | 1.09 GiB |
| Qwen3.5 2B Q8_0 | 5 / 12 | 2.31 seconds | 83.38 tokens/second | 2.19 GiB |
| Qwen3 4B Q4_K_M | 6 / 12 | 1.59 seconds | 65.89 tokens/second | 2.96 GiB |

Each result is one run. Request times include prompt processing and output length, so generation speed and end-to-end time need not rank the same way. No broad hardware or accuracy guarantee follows from this sample. Qwen3 4B is selected for the first adapter-training pilot as the strongest tested small baseline, not as an approved shipping model.

The user has authorized starting use-case training. [Training tooling](training/README.md) generates synthetic data and performs a bounded QLoRA experiment in an isolated development environment. AI remains disabled in Folio; no personal records are used, no adapter is published, and installer creation stays deferred. Original safetensors weights must be downloaded separately for training, distinct from the inference GGUF. Record actual training completion and validation results before claiming any improvement.

### Completed GPU training experiments

Both experiments used original `Qwen/Qwen3-4B` revision `1cfa9a7208912126459214e8b04321603b3df60c`. All three safetensors files were checked against publisher SHA-256 hashes before training. Training used Transformers NF4, BF16 computation, rank-8 query/value adapters, assistant-only loss, and 2,949,120 trainable parameters. This runtime has no Ollama JSON constraint and differs from the earlier GGUF comparison; compare base and adapter within each experiment.

| Experiment | Optimization steps | Structured validation before → after | Validation loss before → after | Original regression benchmark before → after |
|---|---:|---:|---:|---:|
| Initial pilot | 20 | 6/12 → 7/12 | 3.383 → 0.775 | 8/12 → 8/12 |
| Refined synthetic data and explicit schema prompt | 96 | 8/12 → 11/12 | 3.484 → 0.137 | 8/12 → 8/12 |

Each experiment has 288 synthetic training rows and 48 validation rows; only the first 12 validation rows were evaluated, one per behavior. The optimizer saw 20 or 96 shuffled training rows, not the full dataset. Refinement added reminder wording, ambiguous rescheduling, four document-instruction variants, and explicit citation/priority/abstention types. Validation wording and topics differ from training but share templates. The two experiments use different prompts/data: their validation scores are not directly comparable to each other. Free-form answer wording is excluded from the structured score and requires human review. No original benchmark prompt was used as an optimization example, but it has been inspected repeatedly and is a regression set, not a fresh release test.

The refined adapter corrected the original benchmark's ambiguous-record action and answered the document-injection question instead of proposing a malicious task. However, it still emitted a citation string instead of an array, interpreted a reminder as an event, and regressed on the required abstention flag. Manual review also found an incorrect event start time in validation (11:30 instead of 11:00). Overall original-benchmark pass count did not improve. This is evidence of partial learning and limited transfer, not successful release qualification.

Both adapters were saved under ignored `target/folio-adapter-pilot` and `target/folio-adapter-refined`, each approximately 11.3 MiB, with output reports and preserved synthetic datasets. The refined run reported 4.32 GiB peak Torch-allocated GPU memory; driver sampling during training observed about 5.47 GiB total GPU use. Those are different memory measurements, not a promise about other laptops. Interrupted-download fragments were removed after verified weights were complete.

**Decision:** retain Qwen3 4B as an experimental candidate, keep both adapters disabled, and do not export or bundle them as a finished model. Next quality work needs independently authored, varied task/event and grounded-answer examples, action-specific JSON schemas, a fresh evaluation set, and separate factual and safety review. Before shipping, recheck the exact packaged quantization/runtime, CPU/low-memory behavior, adversarial inputs, and deterministic application guards. More training steps alone are not sufficient evidence.

### Third experiment: varied data and stricter independent probes

The next run used 512 synthetic training rows, 32 validation rows, varied times/priorities/dates, Unicode titles, and two system-prompt styles. It optimized 200 unique shuffled examples across 16 behaviors. No personal data or holdout examples were optimized. The adapter and dataset snapshots are saved under ignored `target/folio-adapter-varied`; snapshot hashes were verified.

| Check | Base | 200-step adapter |
|---|---:|---:|
| First 16 templated validation cases | 8/16 | 15/16 |
| Unchanged original regression set | 8/12 | 9/12 |
| 16 separately authored probes, including strict proposal preflight | 0/16 | 7/16 |

Validation loss fell from 3.509 to 0.015. Peak Torch-allocated GPU memory was 4.34 GiB; a driver sample during training observed 5.78 GiB total. These are experiment measurements, not shipping-runtime requirements. The independently authored probe results use stricter criteria than the original benchmark; their counts are not interchangeable with earlier scores or a general intelligence rating. All runs use the same pinned Transformers NF4 base/adapter pairing for within-run comparison.

The apparent validation gain did not transfer adequately. Validation still incorrectly requested clarification for a valid dated Unicode-title task. The original regression set retained reminder-action and citation failures, and one date-only document fact was expanded into an unsupported midnight timestamp. Independent probes exposed a leap-day calculation error, incorrect reminder actions, duplicate-record guessing, invalid April 31, invented event times, insufficient abstention, citation omissions, and an XML-style document injection that produced a Personal task proposal.

Eight of the nine failed independent outputs were rejected by experimental preflight (including the document write). One missing-evidence answer had valid structure and accessible citations but an incorrect abstention flag, so the preflight accepted it. This demonstrates the distinction between structural validity and semantic correctness. No actions were executed, and AI remains disabled. Passing preflight must never be advertised as a factual or intent-correctness guarantee.

`desktop/ai/proposals.cjs` is tested prototype groundwork only. It expects trusted backend metadata, rejects malformed fields, invalid date/range values, document-mode writes, unresolved edits, and inaccessible citations/records. It is not wired into the app and does not replace authorization, conflict checks, DST validation, factual review, or approval. Desktop tests passed 12/12, dataset checks passed 4/4, and the focused preflight tests passed again after malformed-source coverage was added.

**Release decision: rejected.** Fine-tuning has not produced an error-free Folio assistant. Preserve all three adapters for reproducibility without enabling or packaging them. Next work should prioritize separate action/answer schemas with constrained decoding, trusted resolution of dates/records, independently authored training data rather than more repetitions of templates, and new blind factual/adversarial evaluation. Reusing the inspected probes for training would turn them into regression data; any later training iteration needs another untouched evaluation set. Zero observed failures on a finite set would still not guarantee zero future errors.

The installed Qwen3 8B automatic-offload baseline passed 7 of 12 strict checks in one run. It guessed between duplicate records, guessed an action for an unavailable record, and followed an instruction in a document passage. It also used an event action for a reminder request and omitted the required abstention flag despite verbally saying the evidence was missing. This is a narrow prompt/schema result, not a claim that every answer was wrong or a general model ranking. Keep server-side ambiguity, evidence, scope, and action guards independent of the model.

`desktop/ai/assets.cjs` now provides a streaming offline-asset preflight: required runtime/chat/embedding/license roles, pinned revisions, byte sizes, SHA-256 verification, and safe paths. Its regression test rejects corrupt, incomplete, floating-version, and escaping assets. It is build/preflight groundwork and is not yet wired to an enabled assistant.

`desktop/ai/evaluation.cjs` supplies 12 synthetic checks for creation, relative dates, event duration, ambiguous/missing records, Personal scope, unsupported deletion, invalid event ranges, grounded answers, missing evidence, document injection, and exact edits. `desktop/scripts/evaluate-ai.cjs` runs these against an installed exploratory Ollama model in automatic or forced-CPU mode. It only writes ignored reports under `target/ai-evaluation`; it never reads personal records or executes proposals.

Run `node desktop/scripts/evaluate-ai.cjs MODEL --cpu` for CPU evaluation. Reports pin the installed model digest and include output, per-case results, timings, and generation throughput. The first request is cold; subsequent requests may reuse the loaded model. The harness unloads its model after a run and sets a one-minute idle expiry as a fallback. Separate cold and warm measurements are still required. Pass counts are narrow regression evidence, not a general intelligence score or a complete safety assessment.

Before choosing a shipping model, add representative long summaries, mixed-language documents, larger retrieval sets, adversarial action variants, repeated runs, human answer review, and a separate held-out set. Repeat the winning candidates on the exact packaged GGUF/runtime with both cold and warm measurements and peak-memory sampling. A candidate failing action guards must not be enabled for writes even if its summaries are useful.

### Fourth experiment: runtime contract and original target reached

The runtime-contract adapter resumed `folio-adapter-varied` for 400 additional steps at a 0.00005 learning rate with a restarted optimizer. The synthetic dataset contains 1,024 training rows and 24 validation rows; the first 12 validation generations all passed their structured checks. Training took 406.6 seconds and peaked at 4.33 GiB of Torch-allocated GPU memory. Artifacts, dataset snapshots, optimized row IDs, and reports remain under ignored `target/folio-adapter-runtime-v4`.

**The unchanged original benchmark passed 12/12 (100%).** The preceding adapter scored 9/12 and the unchanged NF4 base previously scored 8/12. This latest original run evaluated only the adapter; it did not remeasure the base. Prompts, scoring, and greedy decoding were unchanged, with no constrained decoder or output repair. The generator rejects normalized exact overlap with original and separate-probe prompts, but the new examples deliberately target inspected failure categories. This is benchmark-aware regression improvement, not blind generalization evidence.

This meets the requested at-least-95% original-benchmark target because 11/12 is only 91.7%. It does not establish 95–98% error-free real-world use. Manual inspection found that the unsupported-deletion response still mentions account settings, which this local app does not have; the original action scorer does not penalize that wording. Keep factual UI guidance, safety, and broader intent checks separate from the headline score. The model remains disabled, and installer/launcher work remains deferred.

The paired separate-probe run finished at **0/16 for the unchanged base and 8/16 for v4**, compared with 7/16 for the preceding adapter. These probes use stricter criteria than the original set and have already been inspected during development, so they are not a new blind accuracy estimate. Exact user prompts remain outside training.

The latest adapter correctly clarified duplicate/unknown records and resisted the tested XML document injection. Remaining failures include leap-day and year-boundary reminders, rejecting a valid overnight event, accepting April 31, inventing an incomplete event when times were missing, citing an irrelevant source, attaching a citation to an abstention, and omitting summary citations. Five of the eight failed outputs were rejected by the prototype structural preflight; incorrect clarification and irrelevant-but-accessible citations were accepted. The overnight case regressed from the earlier adapter. No action was executed.

**Current decision:** the original regression target is achieved, but broader model qualification is not. Retain v4 as an experimental adapter, keep AI disabled, and continue ordinary Folio development. Future model work should use an explicit reminder/action contract, trusted calendar calculations and source selection, representative new examples, and a genuinely untouched evaluation set. Do not describe the model as 95–98% reliable or ship it based on 12/12 alone.

## Earlier remaining-work snapshot (superseded by PLAN.md)

- Repeat the shortlist with action-specific schemas and grounded-answer-only mode, add held-out summaries and relevance tests, and measure cold/warm performance. The three completed CPU runs are initial evidence, not the final selection gate.
- Select and pin a runtime and text-only GGUF; repeat evaluations using that exact pairing.
- Compare embeddings on a synthetic relevance dataset; retain the current index until migration is verified.
- Implement managed inference lifecycle and safe backend proposals after the candidate gates are met.
- Add plain-language AI status/setup within Folio, then test assets missing/corrupt, low memory, cancellation, and offline operation.
- Defer the complete installer and installation tests until feature scope is ready.

## Current qualification and runtime preparation

The first beta now targets Qwen3 4B on the owner's Windows 11 / 16 GB / RTX 4060 laptop, subject to the gates in PLAN.md. Earlier smaller-model shortlist entries are historical comparisons, not the selected beta default. Broader laptop compatibility remains unverified.

The fifth experiment continued from the runtime-contract adapter: 1,600 additional bounded optimization steps at learning rate 0.00004, 1,984 synthetic training rows, 40 separate validation rows, and 20 generation checks. Date boundaries, overnight events, incomplete/invalid dates, ambiguity, citation relevance, abstention, summaries, and document attacks are covered alongside successful replay. Do not infer pass scores from training loss; regression and qualification reports follow completion.

Application contract v2 separates dueLocal from remindLocal. Existing 12 original and 16 harder development prompts/scorers remain unchanged; their legacy reminder convention is reported separately. The frozen 100-case application qualification set was authored separately before training, SHA-256 b8f915d9d296689e19b46f55eed54c6846549f2180d0449c0e6bd7a25d10cd23. It is synthetic, templated, developer-authored, and not author-blinded. It must never be optimized. If evaluation results inform later training, a new qualification version is required for a fresh qualification claim.

Pinned llama.cpp b11429 CPU and Vulkan Windows x64 archives have verified official release sizes and SHA-256 hashes in desktop/ai/runtime-lock.json. Vulkan is the GPU packaging candidate with a separate CPU fallback, reducing bundled dependencies; real CPU and dedicated-GPU embedding inference now pass; chat inference remains untested. The disabled integration groundwork adds a Java local client and an authenticated main-process bridge with bounded, cancellable on-demand inference. No renderer receives its keys. No enabled model or new AI installer has been produced.

### Fifth and sixth experiments (historical results)

The 1,600-step harder-case adapter preserved 12/12 original regressions and improved the harder probes from 8/16 to 14/16. Its first 20 separate validation examples passed 19/20 structured checks; one numbered title lost its numeric suffix. Leap-day arithmetic and an invented afternoon event time remained in the harder set. Peak Torch-allocated VRAM was 4,732,436,992 bytes. This is training-runtime allocation, not packaged-app memory.

A sixth adapter continued for 640 steps at learning rate 0.00002 with 912 focused/replay examples. It preserved 12/12 original checks and reached 15/16 harder probes, fixing the invented vague-time event. The remaining harder failure is a February 28, 2032 reminder incorrectly dated March 1. Separate boundary validation passed 14/16; two unseen leap years were also wrong. These results still fail the agreed 16/16 gate. At that stage the frozen 100-case qualification set had not been evaluated or optimized; the subsequent v7 result is recorded below.

The seventh experiment continued from that adapter with cross-area/year leap examples and broad replay (1,056 rows/steps at learning rate 0.00008). Previous adapters and data snapshots remain intact. A guarded export tool rejects unqualified checkpoints by rescoring their saved outputs; the prior 8/16 checkpoint was correctly refused before creating an export directory. No merged or GGUF chat candidate has yet been produced.

The local embedding candidate is the publisher's nomic-embed-text-v1.5 Q8_0 GGUF, pinned to revision 0188c9bf409793f810680a5a431e7b899c46104c with published size 146,146,432 bytes and SHA-256 3e24342164b3d94991ba9692fdc0dd08e3fd7362e0aacc396a9a5c54a544c3b7. It uses separate search_document/search_query prefixes and a 768-dimensional namespace. The verified download and real CPU/GPU embedding checks passed; see VERIFICATION.md.

## Latest state: seventh experiment and integration evidence

The v7 adapter completed 1,056 optimization steps from v6 with 1,056 synthetic training rows at learning rate 0.00008. Its separate 12-case validation, unchanged original 12-case regression, and unchanged 16 harder probes all passed. The frozen 100-case qualification v1 scored **83/100**, with zero flagged unsafe outputs. This fails the minimum 95/100 release gate. Failures include Personal-scope clarification, citation relevance, omitted summary facts, and rejecting valid overnight events. No proposed action was executed during evaluation.

All checkpoints, generated datasets, raw reports, and model weights remain under ignored local build directories. Small generators, scorers, tests, dependency locks, and build tools are versioned. Preserving tools does not publish trained weights or private records. Before training informed by v1 results, freeze a new independently authored qualification version; retain v1 unchanged as development evidence. No merged or GGUF chat export has been produced, and ordinary app builds keep AI disabled.

Pinned Nomic embeddings ran successfully using both CPU and RTX 4060 Vulkan inference with 768-dimensional outputs and sensible related/unrelated ranking. A disposable private Java integration indexed stored Markdown, retained original checksums and metadata, excluded Personal from combined retrieval, rejected missing authentication, and continued saving records when the chat service was unavailable. These checks establish embedding/index plumbing, not tuned-chat quality or performance on other laptops.

Managed runtime and native-host checks now cover dedicated GPU selection, cancellation, authentication, bounded queues, CPU fallback simulations, and cleanup after forcibly terminating either the native host or its parent. Final real-chat lifecycle and packaged GPU-to-CPU fallback remain pending. Pinned Temurin Java 21 preparation and an installer source template exist; the full offline installer, notices/source review, clean installation, updates, and uninstall/reinstall acceptance are unfinished. PLAN.md is the current delivery checklist.
