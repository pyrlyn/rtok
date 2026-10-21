Confirmed on `2c7c40b2d8af0673f085d71d8d3e1da0051084e5` in [pi tool_result](plugins/pi/extensions/rtok.ts#L143). The handler joins only text blocks for `rtok filter`, then returns a replacement content array containing only the compressed text. A successful transformation of a text-plus-image result therefore drops the image; its bytes were never sent to the filter and cannot be recovered from that text's archive. The omp installer links the same package. T70.1 covers text replacement, not preservation of mixed content.

A Vitest probe imported the actual extension with a synthetic registrar and mocked child-process transport. A text/image fixture returned only compressed text; unchanged-output and subprocess-failure controls returned no replacement.

Execution plan:

1. Add a mixed-content regression for text, image and other host-supported blocks, alongside text-only, unchanged and failure controls.
2. Preserve every non-text block and its ordering when replacing text, or conservatively pass mixed content through until a lossless mapping is available. Do not claim that archiving only the joined text preserves the whole result.
3. Cover multiple text blocks separated by images, empty text, unknown block types and the shared omp package path.
4. Run extension/package parity tests and `just check`.

Check: successful compression cannot remove or alter non-text tool-result blocks. Evidence used the actual extension under Vitest; no real pi/omp process or model was invoked.

Result: The pi extension keeps non-text blocks (images) in order; the compressed text replaces the text blocks at the first text block's position.

Check: vitest `plugins/pi/tests` 32 green.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
