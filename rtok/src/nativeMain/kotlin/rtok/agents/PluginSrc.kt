// Port of src/agents/mod.rs `plugin_src`/`resolve_plugin_src`, `install_prefix`, `ketch_store_plugin`,
// `version_dir_key`, `join_rel`: the tree a host plugin is installed from.
package rtok.agents

import rtok.VERSION
import rtok.sys.canonicalize
import rtok.sys.currentExe
import rtok.sys.exists
import rtok.sys.fileName
import rtok.sys.isDir
import rtok.sys.join
import rtok.sys.listDir
import rtok.sys.parent

/** Where a repo-relative plugin tree (`plugins/cline/hooks/rtok-hook`) lives on this machine. */
fun interface PluginSource {
    fun of(rel: String): String
}

/** The plugin trees shipped with the running binary. */
object ShippedPlugins : PluginSource {
    override fun of(rel: String): String = PluginSrc.resolve(rel, currentExe(), null, VERSION)
}

object PluginSrc {
    private const val PREFIX_SHARE_DIR = "share/rtok"
    private val PREFIX_BIN_DIRS = listOf("bin", "Scripts")

    /** [base] joined with [rel] split on `/` and `\`. */
    fun joinRel(
        base: String,
        rel: String,
    ): String = rel.split('/', '\\').filter { it.isNotEmpty() }.fold(base, ::join)

    /**
     * Resolution order: beside [exe], beside the file [exe] links to, `<prefix>/share/rtok` for a `<prefix>/bin` exe,
     * the ketch store for a `<root>/bin` exe, then [devRoot] (the Rust crate dir; no counterpart in a native build),
     * and the beside-exe path for a clear error when nothing exists.
     */
    fun resolve(
        rel: String,
        exe: String?,
        devRoot: String?,
        pkgVersion: String,
    ): String {
        val beside = exe?.let(::parent)?.let { joinRel(it, rel) }
        val real = exe?.let(::canonicalize)
        val dev = devRoot?.let { joinRel(it, rel) }
        val candidates =
            sequence {
                beside?.let { yield(it) }
                real?.let(::parent)?.let { yield(joinRel(it, rel)) }
                for (bin in listOfNotNull(exe, real)) installPrefix(bin)?.let { yield(joinRel(joinRel(it, PREFIX_SHARE_DIR), rel)) }
                ketchRoot(exe)?.let { root -> ketchStorePlugin(root, rel, pkgVersion)?.let { yield(it) } }
                dev?.let { yield(it) }
            }
        return candidates.firstOrNull(::exists) ?: beside ?: dev ?: rel
    }

    /** `<prefix>` when [exe] is `<prefix>/bin/<exe>` (or `Scripts`). */
    private fun installPrefix(exe: String): String? = binParent(exe, PREFIX_BIN_DIRS)

    private fun ketchRoot(exe: String?): String? = exe?.let { binParent(it, listOf("bin")) }

    /** The parent of [exe]'s directory when that directory is one of [names] (any case). */
    private fun binParent(
        exe: String,
        names: List<String>,
    ): String? = parent(exe)?.takeIf { dir -> fileName(dir)?.let { n -> names.any { it.equals(n, ignoreCase = true) } } == true }?.let(::parent)

    /** `<root>/store/rtok/v<pkgVersion>/rel`, else the newest store version (by semver) that has [rel]. */
    internal fun ketchStorePlugin(
        root: String,
        rel: String,
        pkgVersion: String,
    ): String? {
        val store = join(root, "store/rtok")
        val versioned = joinRel(join(store, "v$pkgVersion"), rel)
        if (exists(versioned)) return versioned
        val dirs = runCatching { listDir(store) }.getOrDefault(emptyList()).map { join(store, it) }.filter(::isDir)
        return dirs
            .sortedWith(compareBy(nullsFirst(), { d: String -> VersionKey.parse(fileName(d).orEmpty().removePrefix("v")) }).thenBy { fileName(it) })
            .asReversed()
            .map { joinRel(it, rel) }
            .firstOrNull(::exists)
    }
}

/** SemVer precedence (major.minor.patch, then pre-release identifiers) for picking the newest store folder. */
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
