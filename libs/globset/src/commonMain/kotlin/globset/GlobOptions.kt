package globset

/** globset `GlobBuilder` switches; the defaults are `Glob::new`'s on Unix. */
data class GlobOptions(
    val caseInsensitive: Boolean = false,
    /** When false (the default) `*` and `?` match `/` too. */
    val literalSeparator: Boolean = false,
    val backslashEscape: Boolean = true,
    val emptyAlternates: Boolean = false,
    val allowUnclosedClass: Boolean = false,
)
