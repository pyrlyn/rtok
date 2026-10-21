T672 prints 8 hex chars on a compressed command. `rtok report` still listed each frozen archive as the full 64-character sha in the Expand section (markdown, HTML, PDF and `--ai`). That text is what an agent reads back.

The four renderers now print the 8-character prefix when the stored id is a full hex sha. `rtok expand` resolves it. A shorter id, and text that is not hex, stays as stored, so a prefix already chosen and the hostile-text fixture are not cut. The ledger `ref_id` and the document's `expanded_ids` stay the full sha.

Check: `tests/report.rs` finds the 8-character prefix and not the sha in markdown and HTML; the markdown and HTML one-row snapshots show `ffffffff`; a full sha is absent from the PDF bytes.
