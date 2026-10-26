// Port of src/agents/mod.rs `app_version` + `looks_like_a_version`: a host app's `--version`, bounded in time.
package rtok.agents

import rtok.config.Config
import rtok.sys.ProgressRunner

object AppVersion {
    /** How long one `<bin> --version` probe may run (a CLI that never answers must not hang `agents list`, T280). */
    const val PROBE_LIMIT_MS = 3_000L

    private const val MAX_CHARS = 32

    /**
     * Version of the installed app, or `"-"`: the first `--version` line that looks like a version (npm wrappers print
     * noise first, T168), else the first non-empty line; [MAX_CHARS] max.
     */
    fun of(
        cfg: Config,
        host: String,
        v: Variant,
    ): String = Locate.programs(cfg, host, v).firstNotNullOfOrNull(::probe) ?: "-"

    private fun probe(program: String): String? {
        val out =
            runCatching {
                ProgressRunner("", program, listOf("--version"))
                    .quiet()
                    .timeout(PROBE_LIMIT_MS)
                    .runCapped(CAP)
                    .first
            }.getOrNull() ?: return null
        val text = out.stdout.decodeToString().ifBlank { out.stderr.decodeToString() }
        val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
        return (lines.firstOrNull(::looksLikeAVersion) ?: lines.firstOrNull())?.take(MAX_CHARS)
    }

    private const val CAP = 64L * 1024

    /** A digit, later a dot, later another digit: tells `git version 2.43.0` from `Package extraction took 1234ms`. */
    fun looksLikeAVersion(line: String): Boolean {
        val digit = line.indexOfFirst(Char::isDigit)
        val dot = if (digit < 0) -1 else line.indexOf('.', digit)
        return dot >= 0 && line.substring(dot + 1).any(Char::isDigit)
    }
}
