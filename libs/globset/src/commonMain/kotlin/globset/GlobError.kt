package globset

/** Why a glob does not compile (globset `ErrorKind`), with globset's messages. */
sealed class GlobErrorKind(
    val description: String,
) {
    data object UnclosedClass : GlobErrorKind("unclosed character class; missing ']'")

    data class InvalidRange(
        val start: Char,
        val end: Char,
    ) : GlobErrorKind("invalid range; '$start' > '$end'")

    data object UnopenedAlternates :
        GlobErrorKind("unopened alternate group; missing '{' (maybe escape '}' with '[}]'?)")

    data object UnclosedAlternates :
        GlobErrorKind("unclosed alternate group; missing '}' (maybe escape '{' with '[{]'?)")

    data object DanglingEscape : GlobErrorKind("dangling '\\'")

    /** The class compiled to a byte class the regex engine rejects (globset `ErrorKind::Regex`). */
    data class Regex(
        val reason: String,
    ) : GlobErrorKind(reason)
}

/** A glob that does not compile; the message is globset's `error parsing glob '<glob>': <kind>`. */
class GlobError(
    val glob: String,
    val kind: GlobErrorKind,
) : IllegalArgumentException("error parsing glob '$glob': ${kind.description}")
