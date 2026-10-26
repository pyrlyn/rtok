package semver

/** SemVer 2.0 precedence key for `X.Y.Z[-pre][+build]` (build metadata ignored). Own code, no dependency. */
data class VersionKey(
    val core: List<Long>,
    val pre: List<String>,
) : Comparable<VersionKey> {
    override fun compareTo(other: VersionKey): Int {
        core.zip(other.core).forEach { (a, b) -> if (a != b) return a.compareTo(b) }
        return when {
            pre == other.pre -> 0
            pre.isEmpty() -> 1
            other.pre.isEmpty() -> -1
            else -> comparePre(other.pre)
        }
    }

    private fun comparePre(o: List<String>): Int {
        pre.zip(o).forEach { (a, b) ->
            val c = compareIdent(a, b)
            if (c != 0) return c
        }
        return pre.size.compareTo(o.size)
    }

    private fun compareIdent(
        a: String,
        b: String,
    ): Int {
        val na = a.toLongOrNull()
        val nb = b.toLongOrNull()
        return when {
            na != null && nb != null -> na.compareTo(nb)
            na != null -> -1
            nb != null -> 1
            else -> a.compareTo(b)
        }
    }

    companion object {
        private const val CORE_PARTS = 3

        /** Null for anything that is not `X.Y.Z[-pre][+build]`. */
        fun parse(s: String): VersionKey? {
            val noBuild = s.substringBefore('+')
            val core = noBuild.substringBefore('-').split('.').map { it.toLongOrNull() ?: return null }
            val pre = if ('-' in noBuild) noBuild.substringAfter('-').split('.') else emptyList()
            return if (core.size == CORE_PARTS && pre.none { it.isEmpty() }) VersionKey(core, pre) else null
        }
    }
}
