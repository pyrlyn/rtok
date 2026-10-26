// Port of src/plugins/cmd/bounded.rs — is a Bash snippet already bounded (`sed -n A,Bp`, `| tail -N`,
// `grep -m`)? Plus the small shell lexer the cmd plugin uses to split chains and pipelines.
package rtok.plugins.cmd

import rtok.cmdStem

object Bounded {
    /** Raw output at or below this many bytes is passed through when the command bounded itself. */
    const val MAX_BYTES = 30_000
    /** Characters of a tool result the host shows the model. */
    const val HOST_VISIBLE_CHARS = 30_000

    /** The first [HOST_VISIBLE_CHARS] Unicode scalars of [text]. */
    fun hostVisiblePrefix(text: String): String {
        var i = 0; var k = 0
        while (i < text.length && k < HOST_VISIBLE_CHARS) { i += if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1; k++ }
        return text.substring(0, i)
    }

    private fun silent(pipeline: List<List<String>>) = pipeline.size == 1 && pipeline.last().firstOrNull().let { it == "cd" || it == "export" }

    /** Every non-silent pipeline ends in a stage that bounds its output. */
    fun isBounded(snippet: String): Boolean {
        var any = false
        for (pipeline in lex(snippet)) {
            val last = pipeline.lastOrNull() ?: continue
            if (silent(pipeline)) continue
            if (!bounds(last)) return false
            any = true
        }
        return any
    }

    /** Two or more distinct programs across the chain's stages (wrappers like `mise exec --` peeled). */
    internal fun mixedChain(snippet: String): Boolean {
        val programs = HashSet<String>()
        for (pipeline in lex(snippet)) {
            if (pipeline.isEmpty() || silent(pipeline)) continue
            // Every stage: `git log | grep foo` is two programs (T386: peeled, so two `mise exec` stay two).
            for (stage in pipeline) Formatters.visibleArgv(stage).firstOrNull()?.let { programs += cmdStem(it) }
        }
        return programs.size >= 2
    }

    /** Every stage of every pipeline, in order. */
    internal fun stages(snippet: String): List<List<String>> = lex(snippet).flatten()

    /** Lists (`&&`, `||`, `;`, newline) of pipelines (`|`, `|&`) of stages (words). */
    private const val NONE = '\u0000'

    internal fun lex(s: String): List<List<List<String>>> {
        val lists = mutableListOf(mutableListOf(mutableListOf<String>()))
        // No `Char?` here: the K/N release optimizer turned a boxed `Char?` passed to `StringBuilder.append`
        // into "Unexpected receiver type: kotlin.Char" (`rtok run cat <file>`); `NONE` marks "no char".
        var word: StringBuilder? = null; var quote = NONE; var i = 0
        fun w() = word ?: StringBuilder().also { word = it }
        while (i < s.length) {
            val c = s[i++]; val peek = if (i < s.length) s[i] else NONE
            if (quote != NONE) {
                if (c == quote) quote = NONE
                else if (quote == '"' && c == '\\') {
                    // Inside double quotes only `"` `\` `$` `` ` `` escape (and `\`-newline continues); other `\` stays.
                    when (peek) { '"', '\\', '$', '`' -> { i++; w().append(peek) }; '\n' -> i++; else -> w().append(c) }
                } else w().append(c)
                continue
            }
            val sep: Boolean? = when {
                // Backslash-newline outside quotes is line continuation, not a separator.
                c == '\\' && peek == '\n' -> { i++; null }
                // Any other escaped char is a plain word char: `it\'s` must not open a quote.
                c == '\\' -> { if (peek != NONE) { i++; w().append(peek) } else w().append('\\'); continue }
                c == '\'' || c == '"' -> { quote = c; w(); continue }
                c == '|' && peek == '|' -> true
                c == '&' && peek == '&' -> true
                c == ';' || c == '\n' -> true
                c == '|' -> false
                c.isWhitespace() -> null
                else -> { w().append(c); continue }
            }
            val stages = lists.last()
            word?.let { stages.last() += it.toString(); word = null }
            if (sep != null) {
                if (i < s.length && (s[i] == '|' || s[i] == '&')) i++
                if (sep) lists += mutableListOf(mutableListOf<String>()) else stages += mutableListOf<String>()
            }
        }
        word?.let { lists.last().last() += it.toString() }
        return lists.filter { p -> p.any { it.isNotEmpty() } }
    }

    private fun bounds(stage: List<String>): Boolean {
        val cmd = stage.firstOrNull() ?: return false
        val args = stage.drop(1)
        return when (val stem = cmdStem(cmd)) {
            "head" -> counts(args) { n -> n.all { it in '0'..'9' } }
            "tail" -> args.none { it == "-f" || it == "-F" || it == "--follow" } && counts(args) { !it.startsWith('+') }
            "sed" -> sedRange(args)
            // T177: `-A/-B/-C` bound context per match, not the match count: a recursive `grep` also needs `-m`.
            // `rg` is recursive by default and `-r` is `--replace`, so the extra gate is `grep`-only.
            "grep", "rg" -> {
                if (stem == "grep") {
                    val recursive = args.any { it == "--recursive" || (it.startsWith('-') && !it.startsWith("--") && (it.contains('r') || it.contains('R'))) }
                    if (recursive && args.none(::isMaxCount)) return false
                }
                hasBoundingContext(args)
            }
            // T177: `-n` only numbers lines; exactly one named file, or a multi-file dump passes whole.
            "cat" -> args.any { it == "-n" } && args.count { !it.startsWith('-') } == 1
            else -> false
        }
    }

    private fun shortFlag(a: String) = a.removePrefixOrNull("-")?.takeIf { !it.startsWith('-') }

    private fun hasBoundingContext(args: List<String>) = args.any { a ->
        shortFlag(a)?.trimEnd { it in '0'..'9' }?.let { s -> s.endsWith('A') || s.endsWith('B') || s.endsWith('C') || s.endsWith('m') } == true ||
            a.split('=')[0] in setOf("--context", "--after-context", "--before-context", "--max-count")
    }

    private fun isMaxCount(a: String) = shortFlag(a)?.trimEnd { it in '0'..'9' }?.endsWith('m') == true || a.split('=')[0] == "--max-count"

    private fun counts(args: List<String>, ok: (String) -> Boolean): Boolean {
        val it = args.iterator()
        while (it.hasNext()) {
            val a = it.next()
            val v = when (a) {
                "-n", "-c", "--lines", "--bytes" -> if (it.hasNext()) it.next() else null
                else -> a.removePrefixOrNull("--lines=") ?: a.removePrefixOrNull("--bytes=") ?: a.removePrefixOrNull("-n") ?: a.removePrefixOrNull("-c")
                    ?: a.removePrefixOrNull("-")?.takeIf { n -> n.firstOrNull()?.let { it in '0'..'9' } == true }
            }
            if (v != null && (v.isEmpty() || !ok(v))) return false
        }
        return true
    }

    private fun sedRange(args: List<String>): Boolean {
        val quiet = args.any { it == "-n" || it == "--quiet" || it == "--silent" }
        val scripts = ArrayList<String>(); val it = args.iterator()
        while (it.hasNext()) {
            val a = it.next()
            when {
                a == "-e" || a == "--expression" -> if (it.hasNext()) scripts += it.next()
                a.startsWith('-') -> {}
                scripts.isEmpty() -> scripts += a
            }
        }
        fun num(e: String?) = e != null && e.isNotEmpty() && e.all { it in '0'..'9' }
        fun range(s: String): Boolean {
            val addr = s.trim().takeIf { it.endsWith('p') }?.dropLast(1) ?: return false
            val ends = addr.split(',')
            return ends.size <= 2 && num(ends[0]) && (ends.size == 1 || num(ends[1]))
        }
        return quiet && scripts.isNotEmpty() && scripts.all { s -> s.split(';').all(::range) }
    }
}
