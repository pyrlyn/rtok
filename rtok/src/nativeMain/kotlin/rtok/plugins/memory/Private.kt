// Port of the `<private>` redaction in src/plugins/memory/mod.rs (T548).
package rtok.plugins.memory

/** Case-insensitive, spans lines; a nested tag closes at the first `</private>`. */
private val PRIVATE_TAG = Regex("<private>.*?</private>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

/** Each `<private>` block becomes `[REDACTED]`, nothing trimmed: for text whose exact bytes matter. */
fun redactPrivate(input: String): String = PRIVATE_TAG.replace(input, "[REDACTED]")

/** [redactPrivate], trimmed. */
fun stripPrivate(input: String): String = redactPrivate(input).trim()
