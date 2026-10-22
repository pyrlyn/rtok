Gate for a multimodal token gate (`research.md` §16.3 #9). Screenshots from browser and simulator tools enter the live zone as image blocks; rtok measures bytes of text only, so their share is unknown.
Plan: count `image` content blocks in tool results and user messages per tool; bytes; pixel size from the PNG `IHDR` / JPEG `SOF` header (fixed-offset parse, no new dependency); estimated tokens by the provider's published formula, cited in the code comment and in the row.
Check: fixture with one PNG and one JPEG block; dated row in `research.md` §2. Under 5 % of input tokens → closes with the number; above → a card for downscale-or-OCR goes to the creator.

Do (2026-09-24): `measure::jsonl` records every `image` block in a tool_result or a user message (`ImageBlock`); new `measure::image` reads the pixel size from the PNG `IHDR` / JPEG `SOF` header with a small local base64 decoder (no dependency for a header read) and prices it by the vision docs formula `⌈w/28⌉ × ⌈h/28⌉`, high-resolution tier (2576 px, 4784 tokens), cited in the code. `rtok stats` prints an `images` row (blocks, bytes, est. tokens, resident, share of session input) with one line per source tool. Tests: header sizes and the docs table; fixture with one PNG tool_result and one pasted JPEG. `rtok stats --since 30d` (367 sessions): 211 blocks, 181 122 tokens, resident 0.91 % of session input — under the 5 % gate, so the card closes with the number (`research.md` §2, §16.3 #9).

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5
