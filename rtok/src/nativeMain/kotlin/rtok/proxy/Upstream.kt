// Port of `join_upstream` in src/proxy/wire.rs: the upstream base joined with the client's (already
// percent-encoded) path and query. Own code, no URL library: only the parts the proxy needs.
package rtok.proxy

object Upstream {
    /** `base` + `path` (+ `query`, replacing the base's); throws [IllegalArgumentException] on a bad base or a dot segment. */
    fun join(
        base: String,
        path: String,
        query: String?,
    ): String {
        require(base.isNotEmpty()) { "proxy.upstream is empty" }
        val scheme = base.substringBefore("://", "")
        require(scheme.isNotEmpty() && scheme.all { it.isLetterOrDigit() || it in "+-." } && scheme[0].isLetter()) {
            "proxy upstream URL cannot be a base"
        }
        val rest = base.substring(scheme.length + 3)
        val beforeFragment = rest.substringBefore('#')
        val fragment = if ('#' in rest) "#" + rest.substringAfter('#') else ""
        val noQuery = beforeFragment.substringBefore('?')
        val baseQuery = if ('?' in beforeFragment) beforeFragment.substringAfter('?') else null
        val slash = noQuery.indexOf('/')
        val authority = if (slash < 0) noQuery else noQuery.substring(0, slash)
        require(authority.isNotEmpty()) { "proxy upstream URL has no host" }
        val basePath = if (slash < 0) "" else noQuery.substring(slash)
        val joined = StringBuilder(basePath.trimEnd('/'))
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            require(!isDotSegment(segment)) { "request path has a `.` or `..` segment" }
            joined.append('/').append(segment)
        }
        val q = (query ?: baseQuery)?.let { "?$it" }.orEmpty()
        return "${scheme.lowercase()}://$authority${joined.ifEmpty { "/" }}$q$fragment"
    }

    /** `.` or `..`, also percent-encoded, as the URL standard reads them. */
    private fun isDotSegment(segment: String): Boolean {
        val s = segment.lowercase().replace("%2e", ".")
        return s == "." || s == ".."
    }
}
