// Port of src/agents/mcp.rs `Writer`, `run`, `sync`, `install`, `remove`: rtok's MCP entry for a spec on the real disk,
// written through the agent SDK's `write` (atomic swap, `.backup_configs` copy, dry run) like every other host edit.
package rtok.agents

import rtok.agent.sdk.Apply
import rtok.config.Config
import rtok.mcp.config.McpFs
import rtok.mcp.config.McpSpec
import rtok.sys.exists
import rtok.sys.readToString
import rtok.mcp.config.Mode as McpMode
import rtok.mcp.config.apply as mcpApply

/** The disk [McpRun] writes through; the SDK write does the backup, so [McpFs.backup] stays a no-op. */
class McpWriter(
    private val apply: Apply,
) : McpFs {
    override fun read(path: String): String? = DiskFs.read(path)

    override fun tryRead(path: String): String? = if (exists(path)) readToString(path) else null

    override fun write(
        path: String,
        text: String,
    ) = rtok.agent.sdk.write(apply, path, text, "mcp entry")
}

object McpRun {
    /** Install, update or remove rtok's entry for [spec], returning the line the host reports. */
    fun run(
        cfg: Config,
        spec: McpSpec,
        mode: McpMode,
    ): String {
        val ctx = applyOf(cfg)
        return mcpApply(spec, mode, ctx, McpWriter(ctx), ::isRtokBin).line
    }

    /** [run] for an agents [mode]: remove always takes the entry out; the rest write it while `[setup] mcp` is on (D33). */
    fun sync(
        cfg: Config,
        spec: McpSpec,
        mode: Mode,
    ): String? =
        when {
            mode == Mode.Remove -> run(cfg, spec, McpMode.Remove)
            !cfg.setup.mcp -> null
            mode == Mode.Update -> run(cfg, spec, McpMode.Update)
            else -> run(cfg, spec, McpMode.Install)
        }

    fun install(
        cfg: Config,
        spec: McpSpec,
    ) = run(cfg, spec, McpMode.Install)

    fun remove(
        cfg: Config,
        spec: McpSpec,
    ) = run(cfg, spec, McpMode.Remove)
}
