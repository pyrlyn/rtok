// Port of src/plugins/memory/scrub.rs — strip secrets before an observation narrative is stored.
// Pattern list adapted from agentmemory `src/functions/privacy.ts` `stripPrivateData` (Apache-2.0).
package rtok.plugins.memory

import rtok.sys.takeScalars

private const val FIELD_MAX = 200
private const val SCRUB_MARGIN = 1024

private val SCRUB_TAG = Regex("(?i)<private>[\\s\\S]*?</private>")
private val PRIVATE_KEY = Regex("-----BEGIN [A-Z0-9 ]*PRIVATE KEY(?: BLOCK)?-----[\\s\\S]*?(?:-----END [A-Z0-9 ]*PRIVATE KEY(?: BLOCK)?-----|$)")
private val URL_USERINFO = Regex("(?i)\\b([a-z][a-z0-9+.-]*://)[^\\s/?#@:\"'<>]*:[^\\s/?#@\"'<>]+@")

private val SECRETS =
    listOf(
        "(?i)(?:api[_-]?key|secret|token|password|credential|auth)[\\s]*[=:]\\s*[\"']?[A-Za-z0-9_\\-/.+]{20,}[\"']?",
        "(?i)Bearer\\s+[A-Za-z0-9._\\-+/=]{20,}",
        "sk-proj-[A-Za-z0-9\\-_]{20,}",
        "(?:sk|pk|rk|ak)-[A-Za-z0-9][A-Za-z0-9\\-_]{19,}",
        "sk-ant-[A-Za-z0-9\\-_]{20,}",
        "gh[pus]_[A-Za-z0-9]{36,}",
        "github_pat_[A-Za-z0-9_]{22,}",
        "xoxb-[A-Za-z0-9\\-]+",
        "AKIA[0-9A-Z]{16}",
        "AIza[A-Za-z0-9\\-_]{35}",
        "eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}",
        "npm_[A-Za-z0-9]{36}",
        "glpat-[A-Za-z0-9\\-_]{20,}",
    ).map(::Regex)

/** Replace private tags, keys and token-shaped strings with `[REDACTED]`. */
fun scrubSecrets(input: String): String {
    var r = SCRUB_TAG.replace(input, "[REDACTED]")
    r = PRIVATE_KEY.replace(r, "[REDACTED]")
    r = URL_USERINFO.replace(r, "$1[REDACTED]@")
    for (p in SECRETS) r = p.replace(r, "[REDACTED]")
    return r
}

/**
 * One narrative field, scrubbed, then cut to [FIELD_MAX]. Only a bounded head is scrubbed, since a tool
 * output can be many megabytes and the hook has milliseconds; the margin lets a secret that straddles
 * the cut still match whole. A `<private>` block is redacted only with its closing tag, which may lie
 * past the head, so such text is scrubbed in full.
 */
internal fun scrubbedField(s: String): String {
    val head = s.takeScalars(FIELD_MAX + SCRUB_MARGIN)
    val src = if (head.length < s.length && head.contains("<private>", ignoreCase = true)) s else head
    return truncateChars(scrubSecrets(src), FIELD_MAX)
}
