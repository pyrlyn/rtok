// Port of src/doctor/checklist.rs. The `rtok doctor --fix` checklist (T331.7): what a terminal user sees before
// anything is written. Every removable finding is a numbered line; a number toggles it, `k N` makes copy N the kept
// one of its duplicate, `d` shows the diff of each file, `y` asks for the last confirmation, `q` leaves. Items in a
// shared project file start unselected, since the change reaches teammates. All input goes through [Prompt], so tests
// script the answers.
package rtok.doctor

/** One question to the user: show `screen`, then read a line; `null` at the end of the input. */
fun interface Prompt {
    fun ask(screen: String): String?
}

/** An entry's identity: its file and its key path. */
typealias Key = Pair<String, String>

/** How the checklist ended. */
sealed interface Choice {
    data object Cancel : Choice

    /** Write everything removable except the entries (`source`, `path`) listed here. */
    data class Apply(
        val off: Set<Key>,
    ) : Choice
}

internal object Checklist {
    private const val HELP = "answer a number, k N, d, y or q\n"

    fun key(x: Problem): Key = x.source to x.path

    /** The finding of `found[i]`'s entry that says it is a copy of a duplicate, if it has one. */
    fun copyOf(
        found: List<Problem>,
        i: Int,
    ): Int {
        val k = key(found[i])
        return found.indices.firstOrNull { key(found[it]) == k && found[it].kind.startsWith("duplicate-") } ?: i
    }

    /**
     * Make copy [i] the kept one of its group; the old kept copy becomes an extra. Refused for what the host decides
     * (a server a wider scope overrides) and for a kept copy the user may not edit. The old kept copy's index, or why not.
     */
    @Suppress("ReturnCount")
    fun keepCopy(
        found: MutableList<Problem>,
        i: Int,
    ): Result<Int> {
        val x = found[i]

        fun no(why: String) = Result.failure<Int>(IllegalStateException(why))
        if (!x.kind.startsWith("duplicate-")) return no("only a copy of a duplicate has a kept copy")
        if (!x.detail.startsWith("runs ")) return no("the host decides which of these is used; it cannot be changed here")
        val old = found.indexOfFirst { it.kind == x.kind && it.group == x.group && it.keep }
        if (old < 0) return no("that copy is not part of a duplicate")
        if (!found[old].fixable) return no("the kept copy is not yours to edit, so it stays")
        val kept = found[old].detail.split(" (").first() + " (your choice)"
        found[old] = found[old].copy(detail = x.detail, keep = false)
        found[i] = found[i].copy(detail = kept, keep = true)
        return Result.success(old)
    }

    private fun screen(
        found: List<Problem>,
        items: List<Int>,
        off: Set<Key>,
        shared: (Problem) -> Boolean,
        note: String,
    ): String {
        val out = StringBuilder("${note}Fix selected: toggle N, k N keeps copy N, d diff, y write, q quit\n")
        for ((n, i) in items.withIndex()) {
            val x = found[i]
            val mark = if (key(x) in off) ' ' else 'x'
            val what =
                when (x.kind) {
                    "broken-hook" -> "broken hook ${x.event} `${x.command}`: ${x.detail}"
                    "duplicate-mcp" -> "extra mcp ${x.path} `${x.command}`"
                    else -> "extra hook ${x.event} `${x.command}`"
                }
            val kept = found.firstOrNull { it.kind == x.kind && it.group != null && it.group == x.group && it.keep }?.let { "; kept in ${it.source}" }.orEmpty()
            val team = if (shared(x)) " (shared project file)" else ""
            out.append(" ${(n + 1).toString().padStart(2)} [$mark] $what\n      ${x.source}$team$kept\n")
        }
        return "$out> "
    }

    /**
     * The entries a project's shared file holds start unselected: the change reaches teammates, while its `.local`
     * files and the user's own files do not.
     */
    fun sharedIn(
        cwd: String?,
        home: String?,
    ): (Problem) -> Boolean =
        { x ->
            cwd != null && startsWithPath(x.source, cwd) && home != cwd && !x.source.substringAfterLast('/').contains(".local")
        }

    /** Rust `Path::starts_with`: whole components only. */
    private fun startsWithPath(
        path: String,
        base: String,
    ): Boolean {
        val b = base.trimEnd('/')
        return path == b || path.startsWith("$b/") || (b.isEmpty() && base.startsWith("/"))
    }

    /** The entries that start deselected. */
    fun defaults(
        found: List<Problem>,
        kinds: List<String>,
        shared: (Problem) -> Boolean,
    ): MutableSet<Key> = found.filter { Fix.removable(it, kinds) && shared(it) }.map(::key).toMutableSet()

    /** One line per entry: a hook that is both broken and an extra copy is listed once. */
    fun items(
        found: List<Problem>,
        kinds: List<String>,
    ): List<Int> {
        val seen = mutableSetOf<Key>()
        return found.indices.filter { Fix.removable(found[it], kinds) && seen.add(key(found[it])) }
    }

    /**
     * Run the checklist over [found] (changed in place by `k N`). [preview] renders the diffs of the current selection;
     * nothing here writes.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth", "ReturnCount")
    fun run(
        found: MutableList<Problem>,
        kinds: List<String>,
        shared: (Problem) -> Boolean,
        preview: (List<Problem>, Set<Key>) -> String,
        prompt: Prompt,
    ): Choice {
        val off = defaults(found, kinds, shared)
        var note = ""
        while (true) {
            val items = items(found, kinds)
            val on = items.count { key(found[it]) !in off }
            val line = prompt.ask(screen(found, items, off, shared, note)) ?: return Choice.Cancel
            note = ""
            val words = line.split(Regex("\\s+")).filter { it.isNotEmpty() }

            fun pick(w: String) = w.toUIntOrNull()?.toInt()?.let { n -> items.getOrNull(n - 1).takeIf { n >= 1 } }
            when {
                words == listOf("q") -> {
                    return Choice.Cancel
                }

                words == listOf("d") -> {
                    note = "${preview(found, off)}\n"
                }

                words == listOf("y") && on == 0 -> {
                    note = "nothing is selected\n"
                }

                words == listOf("y") -> {
                    val ask = "${preview(found, off)}\nWrite $on change(s)? [y/N] "
                    if (prompt.ask(ask)?.trim()?.equals("y", ignoreCase = true) == true) return Choice.Apply(off.toSet())
                    note = "not written\n"
                }

                words.size == 2 && words[0] == "k" -> {
                    val i = pick(words[1])
                    if (i == null) {
                        note = "no such item\n"
                    } else {
                        keepCopy(found, copyOf(found, i)).fold(
                            { old ->
                                // The old kept copy is a new line; it starts as any other would.
                                off.remove(key(found[i]))
                                if (shared(found[old])) off.add(key(found[old]))
                            },
                            { note = "${it.message}\n" },
                        )
                    }
                }

                words.size == 1 -> {
                    val i = pick(words[0])
                    if (i == null) {
                        note = HELP
                    } else {
                        val k = key(found[i])
                        if (!off.remove(k)) off.add(k)
                    }
                }

                else -> {
                    note = HELP
                }
            }
        }
    }
}
