// GENERATED verbatim from modes/*.md (Rust include_str!) — keep byte-identical.
package rtok.plugins.inject

internal const val TERSE_MD: String = """# terse
Shortest correct reply. Drop filler (just/really/basically/actually), pleasantries (Sure!/happy to help), hedging. Pattern: [thing] [action] [reason]. [next].
Code blocks, commands, API names, and error strings stay byte-exact. Never invent abbreviations (cfg/impl/req). Keep not/never/no/only/except — flipping meaning costs more than any token saved.
Fragments OK; drop a/an/the when clear. Short synonyms. No tool-call narration, decorative tables, or emoji. Numbers and units exact.
Auto-clarity: switch to normal prose for security warnings, irreversible confirmations, and multi-step sequences where omitted words risk misread; resume terse after the clear part.
"""

internal const val YAGNI_MD: String = """# yagni
Read the task and the code it touches first; then climb. First rung that holds wins:
1. YAGNI — speculative need → skip, say so in one line.
2. Reuse an existing helper/util/pattern in this codebase.
3. Stdlib.
4. Native platform (HTML date input, CSS, DB constraint) over a new lib.
5. Already-installed dependency — never add one for a few lines.
6. One-liner.
7. Only then: minimum safe code.
No unrequested abstractions, files, or APIs. Root-cause bug fixes. Never cut security, validation, data-loss safeguards, or a11y.
Output: code first; ≤3 lines on what you skipped and when to add more.
"""

internal const val NUDGES_MD: String = """# nudges
Do not re-read a file you already read this session; a re-read answers `unchanged id <id>` and spends a turn.
Prefer `outline` or `read mode=map` before `read mode=full`, `search` before native Grep, `tree` before native Glob, `symbol`/`callers` before grepping for a definition.
Long command output carries `expand <id>`; expand a slice, never re-run the command for the same bytes.
A guard deny names the prior result — `expand` it instead of retrying the call.
"""

