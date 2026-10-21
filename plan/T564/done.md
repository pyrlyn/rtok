Source-confirmed: [render](src/plugins/memory/sync.rs#L36) interpolates saved titles verbatim between its markers; [mem_save](src/plugins/memory/mod.rs#L420) accepts nonempty titles containing those markers. A title containing `<!-- /rtok:memory -->` creates an early END. The whole rendered block is hashed when saved, but [block_range](src/plugins/memory/sync.rs#L53) stops at the title's END: the next ordinary sync rejects its own unchanged output as hand-edited. A forced sync leaves the old title tail and actual closing marker behind. A title containing START instead makes the generated file look like multiple blocks.

Execution plan:

1. Add render/save/resync fixtures with START, END and multiline titles; demonstrate rejection of an unchanged generated block and the leftover tail after force.
2. Encode titles in the host-file presentation so they cannot emit structural markers; preserve the stored note title and define a deterministic single-line representation.
3. Validate generated block structure before writing and cover repeat sync, remove, force, budget accounting and preservation of bytes outside the genuine block.

Check: generated output has exactly one structural marker pair, normal repeated sync succeeds, and force/remove leave no generated tail; `just check` passes.

Result: Titles render as one line (control characters become spaces, `<!--` becomes `&lt;!--`); the stored title is unchanged. The generated block is checked to hold exactly one marker pair (`check_block`) before it is written.

Check: `hostile_titles_render_as_one_block_of_single_lines`, `hostile_titles_survive_resync_force_and_remove`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
