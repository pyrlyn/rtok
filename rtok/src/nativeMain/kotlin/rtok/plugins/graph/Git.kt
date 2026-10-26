// Port of the git helpers in src/plugins/graph/mod.rs (`git_stdout_result`, `git_stdout`).
package rtok.plugins.graph

import rtok.sys.ProgressRunner

internal object Git {
    /** Output cap: `git log` over the co-change window or a large diff stays well under it. */
    private const val CAP = 64L shl 20

    /** `git -C root args...` stdout, or an error naming git's stderr. */
    fun stdoutResult(root: String, args: List<String>): Result<ByteArray> = runCatching {
        val (out, _) = ProgressRunner("", "git", listOf("-C", root) + args).quiet().runCapped(CAP)
        if (out.exitCode != 0) {
            val err = out.stderr.decodeToString().trim()
            error("git diff failed: ${err.ifEmpty { "git failed" }}")
        }
        out.stdout
    }

    /** [stdoutResult] with the error swallowed: a non-repo or a missing git is `null`. */
    fun stdout(root: String, args: List<String>): ByteArray? = stdoutResult(root, args).getOrNull()
}
