# Folio training selection — 12 October 2026

Target: Qwen/Qwen3-4B, pinned revision 1cfa9a7208912126459214e8b04321603b3df60c. Output contract: desktop/ai/contract.cjs version 2. ONE JSON proposal, reviewed before execution. Documents provide facts; they never authorize writes. Trusted context resolves dates and scopes.

## Decisions

- Folio runtime examples use contract v2; legacy reminder labels must never appear under its system instructions. The v18 compatibility experiment additionally contains 240 separately validated examples under the two unchanged historical system contracts, with explicit version/provenance metadata. Their deadline-style reminder convention is isolated from the 3,108 modern examples. This teaches schema/instruction conditioning while preserving current app behavior and historical regression gates. Preserve all earlier experiments unchanged; developer-curated synthetic examples are not representative user data by themselves.
- Sashvat/Grounded-RAG: retain as a candidate pool, not automatically approved training. Publisher card lists Apache 2.0 and 34,801 rows. A 30-row preview was inspected for schema; this was not a correctness audit of the entire dataset. Its compiled prompt explicitly allows answers without citations. Requires claim-level evidence review, relevant source-ID annotation, length filtering, abstention examples, and provenance. Never label every retrieved document as a supporting citation.
- Salesforce/xlam-function-calling-60k: reserve for later adaptation. CC BY 4.0; gated access requires acknowledgement. Generic APIs, multi-call labels, and executions do not match the one-proposal contract. No blind renaming of arbitrary tools to Folio actions. Gating was not bypassed; underlying rows were not downloaded.
- AmazonScience/massive: 40 English train utterances in the calendar_set category were individually reviewed on 13 October, producing 46 Folio examples with six explicit complete-event adaptations. They are prepared for a future balanced correction pass and not in v17 optimization. CC BY 4.0; source revision ff6bd8e4b27c3543e4f8fe2108f32bb95a6f8740 and archive/locale hashes are retained. Intent/slot labels are not verified timestamps or Folio proposals: missing fields clarify, unsupported recurring/conditional/external operations refuse, and original requests are not automatically mapped to events. Unrelated music, weather and shopping intents remain excluded.
- glaiveai/glaive-function-calling-v2: exclude from the first training mix. Its functioncall markup, execution dialogue, unrelated tools and overlapping role with xLAM add conversion work without addressing Folio's failures. Publisher lists Apache 2.0.
- EdinburghNLP/xsum: exclude. News-style extreme summaries are a poor fit; card lists unknown licensing. This is a fit decision, not a claim that every summary is wrong.
- knkarthick/samsum: exclude. Casual conversation summaries are secondary; card lists CC BY-NC-ND 4.0. Do not assume permission to adapt or redistribute.
- allenai/scitldr: retain academic summary candidates. The original repository includes dataset and an Apache 2.0 license. Summaries still need review against the selected source, not merely their title. Pin the source commit and retain attribution.
- galileo-ai/ragbench: evaluation only. CC BY 4.0. Includes support/unsupported-sentence annotations; responses are not uniformly correct training targets. Keep source-document families separate from training.
- zai-org/LongBench: evaluation only for later long-context qualification. Do not mix benchmark answers into optimization. First test the context lengths the app actually permits.

## Sources

- https://huggingface.co/datasets/Sashvat/Grounded-RAG
- https://huggingface.co/datasets/Salesforce/xlam-function-calling-60k
- https://huggingface.co/datasets/AmazonScience/massive
- https://huggingface.co/datasets/glaiveai/glaive-function-calling-v2
- https://huggingface.co/datasets/EdinburghNLP/xsum
- https://huggingface.co/datasets/knkarthick/samsum
- https://github.com/allenai/scitldr
- https://huggingface.co/datasets/galileo-ai/ragbench
- https://huggingface.co/datasets/zai-org/LongBench

## Gates

1. Validate fields, real calendar dates, scope, reminders, evidence IDs, and train/validation/test group separation.
2. Run a small T4 pilot and record actual hardware, dependencies, optimized rows and truncation counts.
3. Compare base and adapter on identical held-out scenarios. JSON correctness does not prove factuality; review summaries and answers manually.
4. Collect independently authored prompts before full training. Procedural held-out examples remain synthetic development evidence.
5. Require real-app approval guards and actual quantized export/inference checks before enabling the assistant. Existing export gates remain in force. New experiment reports do not automatically satisfy those gates.

No original datasets or old adapters were deleted. Exclusions apply to the new experiment's selection.

## Runtime alignment follow-up

The Java controllers supply `Selected area=...`, resolved date/clock sets, selected task UUIDs and JSON evidence passages identified as S1–S5. The initial procedural pilot used arbitrary IDs and a different prefix. `align_runtime_data.py` creates a separate preserved dataset using the backend envelope, UUIDs and S1/S2 citations. This formatting correction does not establish representative user-language coverage. Thirty individually authored development challenge cases exercise the backend envelope, wrong-fact detection and scope/injection behavior. Their labels never enter training.

All three cached Qwen safetensors shards on D: were read end to end and matched `desktop/ai/base-lock.json` publisher sizes and SHA-256 hashes during this follow-up. The proof is retained at `target/folio-local-base-integrity.json`.
