// Port of crates/rtok-mcp/src/status.rs + ops.rs: `McpStatus` computed from a config file and a spec, and `apply`,
// the one entry point a host installer calls to install, update or remove its entry.
package rtok.mcp.config

import rtok.agent.sdk.Apply
import rtok.agent.sdk.IsBin
import rtok.agent.sdk.NO_CHANGES
import rtok.agent.sdk.judgeOwned

/** What a host's config carries at `keyPath.<name>`, compared with [McpSpec.entry]. */
sealed interface Entry {
    data object Present : Entry

    data object Missing : Entry

    /** An entry under that name that is not rtok's own; [diff] is a short human-readable diff. */
    data class Stale(
        val diff: String,
    ) : Entry
}

/** One host client's MCP entry status, plus the name a plugin also serves there (D33). */
data class McpStatus(
    val client: Client,
    val entry: Entry,
    val pluginServes: String?,
)

/** Read [spec]'s config through [fs] and compare it with the entry rtok would write. */
fun status(
    fs: McpFs,
    spec: McpSpec,
): McpStatus {
    val want = spec.entry()
    val entry =
        when (val have = McpConfig.readEntry(fs, spec)) {
            null -> Entry.Missing
            want -> Entry.Present
            else -> Entry.Stale("have $have, want $want")
        }
    return McpStatus(spec.client, entry, spec.pluginServes)
}

/** What [apply] should do to the entry. */
enum class Mode { Install, Update, Remove }

/** What [apply] did, or refused to do and why. */
sealed interface Report {
    /** The line a host's `apply` returns. */
    val line: String

    data object NoChanges : Report {
        override val line = NO_CHANGES
    }

    /** `<key>.<name>: <command> <args>` for an install or update, `- <key>.<name>` for a remove. */
    data class Wrote(
        override val line: String,
    ) : Report

    /** `leave …` for an entry that is not rtok's or that the user edited, `? …` on a dry run that would ask. */
    data class Left(
        override val line: String,
    ) : Report
}

/** Install, update or remove [spec]'s entry under [ctx]; never silently removes an entry that is not rtok's own (T246). */
fun apply(
    spec: McpSpec,
    mode: Mode,
    ctx: Apply,
    fs: McpFs,
    isBin: IsBin,
): Report {
    val ours = spec.entry()
    val at = spec.at()
    return when (mode) {
        Mode.Install, Mode.Update -> {
            when (McpConfig.writeEntry(fs, spec, ours)) {
                Written.Unchanged -> Report.NoChanges
                Written.Changed -> Report.Wrote("$at: ${spec.summary()}")
            }
        }

        Mode.Remove -> {
            remove(spec, ctx, fs, isBin)
        }
    }
}

private fun remove(
    spec: McpSpec,
    ctx: Apply,
    fs: McpFs,
    isBin: IsBin,
): Report {
    val have = McpConfig.readEntry(fs, spec) ?: return Report.NoChanges
    val leave = judgeOwned(ctx, "${spec.at()} in ${spec.configPath}", have, spec.entry(), isBin)
    return when {
        leave != null -> Report.Left(leave)
        McpConfig.removeEntry(fs, spec) == Written.Changed -> Report.Wrote("- ${spec.at()}")
        else -> Report.NoChanges
    }
}
