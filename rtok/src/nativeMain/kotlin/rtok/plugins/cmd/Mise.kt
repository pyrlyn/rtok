// Port of src/plugins/cmd/mise.rs — drop the tool-install and download chatter `mise run|exec` prints.
package rtok.plugins.cmd

import rtok.sys.asciiLowercase
import rtok.sys.rustLines

object Mise {
    /** `mise node@20.1.0 download …` — a step the install prints, not a result. */
    private val STEP = Regex("^mise [\\w.:/+-]+@\\S+ (download|downloading|extract|extracting|checksum|verify|verifying|install|installing|compile|configure|make)\\b")

    /** A progress bar (`[=====>   ]`) or a byte counter (`12.3MiB/45MiB`). */
    private val PROGRESS = Regex("\\[[#=>. -]{4,}\\]|\\d+(\\.\\d+)? ?[KMGT]i?B ?/ ?\\d+(\\.\\d+)? ?[KMGT]i?B")

    private fun problem(line: String): Boolean = line.asciiLowercase().let { low -> listOf("error", "warn", "fail", "panic").any { low.contains(it) } }

    /** The last `\r` frame of a line (what the terminal shows), without colour codes. */
    private fun visible(raw: String): String = stripAnsi(raw.split('\r').lastOrNull { it.isNotEmpty() } ?: "")

    /** [output] without install/progress lines and one marker line counting them, or null when none went. */
    fun tidy(output: String): String? {
        val out = ArrayList<String>(); var dropped = 0; var markerAt = -1
        for (raw in output.rustLines()) {
            val line = visible(raw)
            if (!problem(line) && (STEP.containsMatchIn(line) || PROGRESS.containsMatchIn(line))) {
                dropped++; if (markerAt < 0) markerAt = out.size
            } else out += raw
        }
        if (dropped == 0) return null
        out.add(maxOf(markerAt, 0), "mise: $dropped install/progress lines dropped")
        return shorterOrNull(out.joinToString("\n"), output)
    }
}
