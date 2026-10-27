package globset

/**
 * A compiled glob with globset 0.4.20 semantics (`Glob::new` / `GlobBuilder`): by default `*` and
 * `?` cross `/`, `**` as a whole component is recursive, `{a,b}` alternates (nesting allowed),
 * `[...]`/`[!...]` classes, `\` escapes. Matching is on the whole path's UTF-8 bytes.
 */
class Glob private constructor(
    val glob: String,
    val options: GlobOptions,
    private val nfa: Nfa,
) {
    fun isMatch(path: String): Boolean = nfa.matches(path.encodeToByteArray())

    override fun toString(): String = glob

    companion object {
        /** Compiles [glob]; throws [GlobError] the way globset returns `Err`. */
        fun new(
            glob: String,
            options: GlobOptions = GlobOptions(),
        ): Glob {
            val tokens = Parser(glob, options).parse()
            val nfa = Nfa.Builder(options, glob).build(tokens)
            return Glob(glob, options, nfa)
        }
    }
}

/** globset `GlobSet`: matches when any of its globs does. */
class GlobSet(
    val globs: List<Glob>,
) {
    val isEmpty: Boolean get() = globs.isEmpty()

    fun isMatch(path: String): Boolean = globs.any { it.isMatch(path) }

    companion object {
        val EMPTY = GlobSet(emptyList())
    }
}
