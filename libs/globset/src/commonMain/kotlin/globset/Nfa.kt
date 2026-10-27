package globset

/**
 * The byte automaton globset's regex is: `(?-u)^...$` over the path's UTF-8 bytes, so `?` is one
 * byte, `.` never matches `\n`, and `(?i)` folds ASCII only. Simulated as a Thompson NFA (no
 * backtracking blow-up).
 */
internal class Nfa private constructor(
    private val preds: List<((Int) -> Boolean)?>,
    private val next: List<IntArray>,
    private val start: Int,
    private val accept: Int,
) {
    fun matches(bytes: ByteArray): Boolean {
        var current = closure(setOf(start))
        for (b in bytes) {
            val v = b.toInt() and BYTE
            val moved = HashSet<Int>()
            for (s in current) if (preds[s]?.invoke(v) == true) moved.addAll(next[s].toList())
            if (moved.isEmpty()) return false
            current = closure(moved)
        }
        return accept in current
    }

    private fun closure(states: Set<Int>): Set<Int> {
        val seen = HashSet(states)
        val stack = ArrayDeque(states)
        while (stack.isNotEmpty()) {
            val s = stack.removeLast()
            if (preds[s] == null) next[s].forEach { if (seen.add(it)) stack.addLast(it) }
        }
        return seen
    }

    /** States under construction: a byte state has a predicate and one successor, an epsilon state none and any. */
    internal class Frags {
        val preds = ArrayList<((Int) -> Boolean)?>()
        val next = ArrayList<MutableList<Int>>()

        private fun state(pred: ((Int) -> Boolean)?): Int {
            preds.add(pred)
            next.add(ArrayList())
            return preds.lastIndex
        }

        fun link(
            from: Int,
            to: Int,
        ) {
            next[from].add(to)
        }

        fun eps(): Frag = state(null).let { Frag(it, it) }

        fun byte(pred: (Int) -> Boolean): Frag {
            val s = state(pred)
            val out = state(null)
            link(s, out)
            return Frag(s, out)
        }

        fun seq(parts: List<Frag>): Frag {
            if (parts.isEmpty()) return eps()
            parts.zipWithNext().forEach { (a, b) -> link(a.exit, b.entry) }
            return Frag(parts.first().entry, parts.last().exit)
        }

        fun alt(options: List<Frag>): Frag {
            val inn = state(null)
            val out = state(null)
            options.forEach {
                link(inn, it.entry)
                link(it.exit, out)
            }
            return Frag(inn, out)
        }

        fun star(body: () -> Frag): Frag {
            val hub = state(null)
            val b = body()
            link(hub, b.entry)
            link(b.exit, hub)
            return Frag(hub, hub)
        }
    }

    /** A fragment from `entry` to `exit`. */
    internal class Frag(
        val entry: Int,
        val exit: Int,
    )

    /** Token list to automaton, mirroring globset's `tokens_to_regex`. */
    internal class Builder(
        private val opts: GlobOptions,
        private val glob: String,
    ) {
        private val f = Frags()

        private fun eq(c: Int): (Int) -> Boolean =
            if (opts.caseInsensitive) {
                { it == c || it == swapCase(c) }
            } else {
                { it == c }
            }

        private fun dot(): Frag = f.byte { it != NEWLINE }

        private fun slash(): Frag = f.byte(eq(SLASH))

        private fun dotStar(): Frag = f.star { dot() }

        private fun anyOne(): Frag = if (opts.literalSeparator) f.byte { it != SLASH } else dot()

        fun build(tokens: List<Token>): Nfa {
            val whole = if (tokens == listOf(Token.RecursivePrefix)) dotStar() else tokens(tokens)
            val accept = f.eps().entry
            f.link(whole.exit, accept)
            return Nfa(f.preds, f.next.map { it.toIntArray() }, whole.entry, accept)
        }

        private fun tokens(tokens: List<Token>): Frag {
            val parts = ArrayList<Frag>()
            var i = 0
            while (i < tokens.size) {
                val tok = tokens[i]
                if (tok is Token.Literal) {
                    val run = StringBuilder()
                    while (i < tokens.size && tokens[i] is Token.Literal) run.append((tokens[i++] as Token.Literal).c)
                    run.toString().encodeToByteArray().forEach { parts.add(f.byte(eq(it.toInt() and BYTE))) }
                    continue
                }
                token(tok)?.let(parts::add)
                i++
            }
            return f.seq(parts)
        }

        private fun token(tok: Token): Frag? =
            when (tok) {
                is Token.Literal -> {
                    error("literals run in tokens()")
                }

                Token.Any -> {
                    anyOne()
                }

                Token.ZeroOrMore -> {
                    f.star { anyOne() }
                }

                Token.RecursivePrefix -> {
                    f.alt(listOf(f.alt(listOf(slash(), f.eps())), f.seq(listOf(dotStar(), slash()))))
                }

                Token.RecursiveSuffix -> {
                    f.seq(listOf(slash(), dotStar()))
                }

                Token.RecursiveZeroOrMore -> {
                    f.alt(listOf(slash(), f.seq(listOf(slash(), dotStar(), slash()))))
                }

                is Token.Class -> {
                    f.byte(ByteClass.of(tok, opts.caseInsensitive, glob))
                }

                is Token.Alternates -> {
                    val kept = tok.branches.filter { it.isNotEmpty() || opts.emptyAlternates }
                    if (kept.isEmpty()) null else f.alt(kept.map { tokens(it) })
                }
            }
    }

    internal companion object {
        const val BYTE = 0xFF
        const val NEWLINE = 0x0A
        const val SLASH = 0x2F

        fun swapCase(c: Int): Int =
            when (c) {
                in 'a'.code..'z'.code -> c - CASE
                in 'A'.code..'Z'.code -> c + CASE
                else -> c
            }

        private const val CASE = 32
    }
}
