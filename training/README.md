# Folio adapter-training experiment

This is developer tooling, not a feature enabled in the desktop app. End users will not install Python or train models. No installer is being built during this experiment.

## Scope

Train a small LoRA adapter for JSON proposals, task/event fields, record ambiguity, Personal-area boundaries, grounded document answers, abstention, and ignoring instructions inside documents. Training does not replace validation, target selection, user approval, or document retrieval. Keep changing personal facts in retrieved context rather than model weights.

`build_dataset.py` generates 288 training and 48 validation rows from synthetic topics, dates, records, and documents. Validation uses different topics and wording. The original 12-case comparison set is not included. The generated manifest records counts and hashes. These small templated sets are pilot data: broader independently authored examples, manual label review, and untouched tests are required before release.

`train_adapter.py` performs a bounded QLoRA pilot with a pinned original-publisher model revision, 4-bit NF4 base weights, rank-8 adapters on query/value projections, assistant-only loss, one example per step, and at most 512 tokens. It saves adapter weights and a report under ignored `target/`; it refuses to overwrite an existing adapter or silently train on CPU.

## Reproduction

Use an isolated Python 3.12 environment. Install a CUDA-enabled PyTorch build and Transformers, PEFT, Accelerate, and bitsandbytes. `requirements-lock.txt` records the tested environment, including PyTorch's CUDA 12.8 build (install that from the PyTorch CUDA index first). The GPU pilot needs original safetensors weights; Ollama's inference GGUF is not used as trainable base weights. Cache pretrained files under an ignored development directory, never upload local Folio records or credentials.

1. Run `python training/build_dataset.py`.
2. Verify CUDA is available and no inference model is still loaded. Run `python training/download_weights.py` to prefetch the pinned model with resumable byte ranges and publisher SHA-256 verification. Interrupted downloads remain under ignored `target/` and are resumed on the next run.
3. Run `python training/train_adapter.py --model Qwen/Qwen3-4B --revision 1cfa9a7208912126459214e8b04321603b3df60c --steps 20`.
4. Inspect the saved report, adapter artifacts, validation loss, and trainable-parameter count.
5. Run `python training/evaluate_adapter.py --adapter target/folio-adapter-pilot` to compare base and adapter on the original 12-case benchmark using the same Transformers NF4 runtime and greedy decoding. This is distinct from the earlier Ollama GGUF measurements. Check quality regressions before merging or exporting anything.

The pilot itself measures loss and generated outputs on the first 12 validation examples, one per behavior. Its structured-field check excludes free-form answer wording and is not a factual-quality or full safety score. Review the saved text manually. The initial 20-step pilot optimized only 20 shuffled examples. The refined experiment uses `--steps 96 --output target/folio-adapter-refined` and optimizes 96 distinct shuffled examples, still less than a complete pass over the 288-row dataset. It records optimized row IDs and saves copies of both datasets and their manifest with the adapter. The original benchmark remains outside optimization; repeated inspection makes it a regression set, so release evaluation also needs a fresh untouched set.

A falling loss or a completed 20-step pilot does not establish useful model improvement. Do not ship the adapter until generation-based checks, broader document summaries, scope/action safeguards, CPU/GPU resource checks, and quantized-export round-trip tests pass. Do not call a pilot a completed Folio model.

## Varied-data experiment

`build_robust_dataset.py` generates 512 training rows and 32 validation rows with varied times, deadlines, priorities, two system-prompt styles, reminder boundaries, Unicode titles, missing time/date information, invalid dates, and document attacks. The sets remain synthetic templates rather than a representative user dataset. The initial comparisons above used the earlier generator; saved adapter directories retain their own dataset snapshots.

Run the varied-data builder, then `python training/train_adapter.py --revision 1cfa9a7208912126459214e8b04321603b3df60c --steps 200 --validation-limit 16 --output target/folio-adapter-varied`. This optimizes 200 shuffled examples, not all 512. It evaluates the first 16 validation examples, covering each behavior once. Adapter weights are saved before post-training generation; `training-state.json` distinguishes saved training from completed evaluation.

Compare with `python training/evaluate_adapter.py --adapter target/folio-adapter-varied` for the original regression set and add `--suite fresh` for 16 separately authored probes in `desktop/ai/holdout.cjs`. Those probes are not used as training examples; later generators read their prompts only to reject exact overlap. They exercise leap/year boundaries, overnight events, ambiguity, unavailable records, missing evidence, source injection, and summaries. They are a small development holdout, not evidence of zero errors on future input.

The fresh scorer also uses `desktop/ai/proposals.cjs`, an experimental deterministic preflight. It rejects malformed fields, document-mode writes, out-of-scope sources/records, unresolved edits, and invalid calendar dates/ranges. Trusted context must come from a backend resolver, not the model or renderer. This prototype is not wired into the app, does not execute actions, and does not establish factual support, correct user-intent interpretation, timezone/DST validity, backend authorization, or conflict-free edits. Real backend checks and review remain required. AI stays disabled until integration and release gates pass.

## Runtime-contract experiment

`build_runtime_dataset.py` creates 1,024 training rows (768 runtime-contract examples plus 256 varied replay examples) and 24 validation rows. It reuses the original benchmark's system contract with different dates, and teaches reminder intent, citation arrays, abstention, ambiguity, and document-instruction resistance. It reads evaluation prompts only to reject normalized exact overlap. This is explicitly benchmark-aware development, not blind evaluation; exact non-overlap alone does not demonstrate generalization.

The fourth experiment resumes the varied adapter with a new optimizer:

```text
python training/build_runtime_dataset.py
python training/train_adapter.py --revision 1cfa9a7208912126459214e8b04321603b3df60c --resume-adapter target/folio-adapter-varied --steps 400 --learning-rate 0.00005 --skip-before-generation --validation-limit 12 --output target/folio-adapter-runtime-v4
python training/evaluate_adapter.py --adapter target/folio-adapter-runtime-v4 --adapter-only
python training/evaluate_adapter.py --adapter target/folio-adapter-runtime-v4 --suite fresh
python -m unittest discover -s training -p "test_*.py"
```

`--adapter-only` omits an unchanged base comparison; omit it for a paired check. Neither option changes the prompts, scorer, or greedy decoding. The original set contains only 12 cases: 11/12 is 91.7%, so reaching at least 95% on this set requires 12/12. That result would meet a regression target, not establish 95–98% reliability on future requests.

Sources: [PEFT quantized training](https://huggingface.co/docs/peft/developer_guides/quantization), [Transformers bitsandbytes support](https://huggingface.co/docs/transformers/quantization/bitsandbytes), [original Qwen3 4B](https://huggingface.co/Qwen/Qwen3-4B).

## Current work and generated artifacts

The latest experiment is v7: 12/12 original checks, 16/16 harder probes, and 83/100 frozen synthetic qualification cases. It is not qualified for release. See ../LOCAL_AI.md and ../PLAN.md for current results and the export/integration gates; earlier commands above describe individual historical experiments.

Keep generated training/validation rows, qualification fixtures, model weights, adapters, raw reports, and checkpoints local. Git contains their generators, scoring tools, tests, and dependency pins. Before dataset tests that require qualification fixtures, run `python training/freeze_qualification.py`; these generated fixtures live under ignored `target/`. Do not use qualification prompts or expected answers for optimization. Freeze a new qualification version before a later training pass informed by evaluated failure categories.
