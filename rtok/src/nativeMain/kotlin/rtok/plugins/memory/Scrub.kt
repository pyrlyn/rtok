// Port of src/plugins/memory/scrub.rs — strip secrets before an observation narrative is stored.
// Pattern list adapted from agentmemory `src/functions/privacy.ts` `stripPrivateData` (Apache-2.0).
package rtok.plugins.memory

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
