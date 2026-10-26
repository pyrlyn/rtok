// Port of src/plugins/graph/status.rs (T68.3, T60.1): index health for one root, and mod.rs `cli_root`.
package rtok.plugins.graph

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import rtok.plugin.sdk.Ctx

@Serializable
data class GraphStatus(
    val root: String,
    val rows: Long,
    val files: Long,
    val pending: List<String>,
    val watch: String,
    @SerialName("indexed_at") val indexedAt: Long?,
)

object Status {
    fun collect(
        cx: Ctx,
        root: String,
    ): GraphStatus {
        val key = Index.canon(root)
        return GraphStatus(key, cx.symbolCount(key), cx.symbolFileCount(key), GraphCore.pendingPaths(cx, root), graphCfg(cx).watch, cx.symbolIndexedAt(key))
    }

    /** Rendered for `rtok graph status` and the Graph page (T230). */
    fun formatTable(s: GraphStatus): String =
        buildString {
            append("root ${s.root}\nrows ${s.rows}\nfiles ${s.files}\npending ${s.pending.size}\n")
            for (p in s.pending) append("  $p\n")
            append("watch ${s.watch}\n")
            append("indexed_at ${s.indexedAt ?: "-"}\n")
        }

    /** The root of a one-project subcommand: [path] or the cwd, which must be a directory. */
    fun cliRoot(path: String?): String {
        val root = path ?: rtok.sys.cwd()
        if (!rtok.sys.exists(root)) throw IllegalStateException("$root: No such file or directory (os error 2)")
        if (!rtok.sys.isDir(root)) throw IllegalStateException("$root: not a directory")
        return root
    }
}
