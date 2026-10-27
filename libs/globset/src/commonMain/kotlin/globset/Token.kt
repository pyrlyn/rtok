package globset

/** One glob token (globset 0.4.20 `Token`). */
internal sealed interface Token {
    data class Literal(
        val c: Char,
    ) : Token

    data object Any : Token

    data object ZeroOrMore : Token

    data object RecursivePrefix : Token

    data object RecursiveSuffix : Token

    data object RecursiveZeroOrMore : Token

    data class Class(
        val negated: Boolean,
        val ranges: List<Pair<Char, Char>>,
    ) : Token

    data class Alternates(
        val branches: List<List<Token>>,
    ) : Token
}
