package globset

/**
 * A glob class as globset's regex reads it: each range end is written as its UTF-8 bytes, so
 * `[é-ü]` becomes the byte class `[\xc3\xa9-\xc3\xbc]` (`\xc3`, `\xa9-\xc3`, `\xbc`).
 * A negated class matches any other byte, `\n` included.
 */
internal object ByteClass {
    private sealed interface Atom {
        data class B(
            val v: Int,
        ) : Atom

        data object Dash : Atom
    }

    fun of(
        cls: Token.Class,
        caseInsensitive: Boolean,
        glob: String,
    ): (Int) -> Boolean {
        val atoms = ArrayList<Atom>()
        for ((a, b) in cls.ranges) {
            atoms.addAll(bytes(a))
            if (a != b) {
                atoms.add(Atom.Dash)
                atoms.addAll(bytes(b))
            }
        }
        val ranges = ArrayList<IntRange>()
        var dash = false
        for (atom in atoms) {
            when {
                atom == Atom.Dash -> {
                    dash = true
                }

                dash -> {
                    val lo = ranges.removeLast().first
                    val hi = (atom as Atom.B).v
                    if (hi < lo) throw GlobError(glob, GlobErrorKind.Regex("invalid character class range"))
                    ranges.add(lo..hi)
                    dash = false
                }

                else -> {
                    (atom as Atom.B).v.let { ranges.add(it..it) }
                }
            }
        }
        val inSet: (Int) -> Boolean =
            if (caseInsensitive) {
                { v -> ranges.any { v in it || Nfa.swapCase(v) in it } }
            } else {
                { v -> ranges.any { v in it } }
            }
        return if (cls.negated) ({ !inSet(it) }) else inSet
    }

    private fun bytes(c: Char): List<Atom> = c.toString().encodeToByteArray().map { Atom.B(it.toInt() and Nfa.BYTE) }
}
