// Port of src/agents/mcp.rs (surfaces + status side): `has_entry`/`has_toml_entry`, `McpSurface`, `EntryState`,
// `McpRow` (serves/found/text), `surfaces` over the hosts' MCP files, `status`, `stale_diff`, `unresolved_command`,
// `doctor_line`, `json_spec`, `tilde`. The install side (`rows`/`own_row` over `installed`, `doctor_lines`, `run`/`sync`)
// lands with the per-host `installed`/`apply` port.
package rtok.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.cmdStem
import rtok.config.Config
import rtok.mcp.config.Client
import rtok.mcp.config.DuplicateName
import rtok.mcp.config.Entry
import rtok.mcp.config.EntryShape
import rtok.mcp.config.Format
import rtok.mcp.config.Launch
import rtok.mcp.config.McpConfig
import rtok.mcp.config.McpFs
import rtok.mcp.config.McpSpec
import rtok.mcp.config.RTOK
import rtok.sys.isFile

/** One MCP surface of a host variant: the spec status checks, and what else serves `"rtok"` there (a plugin, an import). */
data class McpSurface(
    val label: String,
    val spec: McpSpec,
    val plugin: String? = null,
)

/** `rtok_mcp`'s [Entry] as `agents info --json` spells it. */
enum class EntryState(
    val json: String,
) {
    Present("present"),
    Missing("missing"),
    Stale("stale"),
}

/** One [McpSurface]'s status, plus the file and, for a stale entry or an unreadable file, what is wrong. */
data class McpRow(
    val surface: String,
    val file: String,
    val entry: EntryState,
    val diff: String? = null,
    val plugin: String? = null,
) {
    /** rtok's server reaches this surface as rtok would run it: its own entry, or a plugin. */
    fun serves() = entry == EntryState.Present || plugin != null

    /** Installed for the `mcp` module: an entry exists (even a stale one) or a plugin serves it. */
    fun found() = entry != EntryState.Missing || plugin != null

    /** `entry <file>`, `stale (<diff>)`, `missing (<file> has no "rtok")`, `plugin <name> (serves "rtok")`. */
    fun text(): String {
        val name = RTOK.name
        val entryText =
            when (entry) {
                EntryState.Present -> {
                    "entry $file"
                }

                EntryState.Stale -> {
                    "stale (${diff.orEmpty()})"
                }

                EntryState.Missing -> {
                    when {
                        plugin != null -> null
                        diff != null -> "missing ($diff)"
                        else -> "missing (${file.substringAfterLast('/')} has no \"$name\")"
                    }
                }
            }
        val pluginText = plugin?.let { "plugin $it (serves \"$name\")" }
        return listOfNotNull(entryText, pluginText).joinToString(", ")
    }

    /** The `agents info --json` object. */
    fun toJson(): JsonObject =
        JsonObject(
            buildMap {
                put("surface", JsonPrimitive(surface))
                put("file", JsonPrimitive(file))
                put("entry", JsonPrimitive(entry.json))
                diff?.let { put("diff", JsonPrimitive(it)) }
                put("plugin", JsonPrimitive(plugin))
            },
        )
}

object McpSurfaces {
    /**
     * Every MCP surface [host]'s [kind] variant reads, with the file its own installer writes. Empty for a host with no
     * MCP entry of its own or one in a shape `rtok_mcp` does not parse (aider, pi, antigravity; continue, goose, reasonix).
     * [plugin] is what serves `"rtok"` there besides the entry (the rtok plugin when it carries MCP and is installed);
     * [grokImport] is Grok's `[compat.claude]` import of `~/.claude.json`.
     */
    fun surfaces(
        host: String,
        cfg: Config,
        kind: Kind,
        plugin: String? = null,
        grokImport: Boolean = false,
    ): List<McpSurface> {
        fun row(
            label: String,
            file: McpFile,
        ) = surface(host, label, kind, file, plugin)
        return when (host) {
            "vscode" -> {
                McpFiles.vscode(cfg).map { (label, file) -> row(label, file) }
            }

            "grok" -> {
                val grok = row(kind.word, McpFiles.BY_HOST.getValue(host)(cfg, kind))
                listOf(grok.copy(plugin = grok.plugin ?: "[compat.claude] import".takeIf { grokImport }))
            }

            else -> {
                listOfNotNull(McpFiles.BY_HOST[host]?.invoke(cfg, kind)?.let { row(kind.word, it) })
            }
        }
    }

    private fun surface(
        host: String,
        label: String,
        kind: Kind,
        mcpFile: McpFile,
        plugin: String?,
    ) = McpSurface(
        label,
        McpSpec(
            host = host,
            client = if (kind == Kind.Cli) Client.Cli else Client.Desktop,
            configPath = mcpFile.path,
            // A strict-JSON file parses as JSONC too; status never writes.
            format = if (mcpFile.path.endsWith(".toml")) Format.Toml else Format.Jsonc,
            keyPath = if (mcpFile.key == AMP_SERVERS) listOf(mcpFile.key) else McpSpec.keyPath(mcpFile.key),
            server = RTOK,
            launch = Launch(),
            entryShape = EntryShape.Command,
            duplicateName = DuplicateName.ShowsBoth,
            pluginServes = plugin?.let { RTOK.name },
        ),
        plugin,
    )

    /**
     * `rtok_mcp` status for one surface. Its strict equality holds only for `{command: "rtok", args: ["mcp"]}`; a host
     * that writes an absolute binary or extra keys reads as stale there, so a stale entry is judged again by its argv.
     */
    fun status(
        fs: McpFs,
        s: McpSurface,
        home: String = McpPaths.home(),
    ): McpRow {
        val (entry, diff) =
            runCatching {
                rtok.mcp.config
                    .status(fs, s.spec)
                    .entry
            }.fold(
                onSuccess = { e ->
                    when (e) {
                        Entry.Present -> EntryState.Present to null
                        Entry.Missing -> EntryState.Missing to null
                        is Entry.Stale -> judgeStale(fs, s)
                    }
                },
                onFailure = { e -> EntryState.Missing to (e.message ?: e.toString()) },
            )
        return McpRow(s.label, tilde(s.spec.configPath, home), entry, diff, s.plugin)
    }

    private fun judgeStale(
        fs: McpFs,
        s: McpSurface,
    ): Pair<EntryState, String?> {
        val have = runCatching { McpConfig.readEntry(fs, s.spec) }.getOrNull()
        val diff = have?.let { McpArgv.staleDiff(it, s.spec.host) }
        return when {
            have == null -> EntryState.Missing to null
            diff == null -> EntryState.Present to null
            else -> EntryState.Stale to diff
        }
    }

    /** T932: a present row on the real disk is stale once its command does not resolve. */
    fun spawnable(
        row: McpRow,
        s: McpSurface,
        fs: McpFs = DiskFs,
        mcpCommand: () -> String = ::rtokMcpCommand,
    ): McpRow {
        val why =
            if (row.entry != EntryState.Present) {
                null
            } else {
                runCatching { McpConfig.readEntry(fs, s.spec) }.getOrNull()?.let { McpArgv.unresolvedCommand(McpArgv.argvOf(it), mcpCommand) }
            }
        return if (why == null) row else row.copy(entry = EntryState.Stale, diff = why)
    }

    /**
     * `missing: <file> has no "rtok" …` or `stale: <file> runs … — rtok side: rtok agents install <host> [--cli|--desktop]`;
     * null for a present entry, or a missing one something other than the rtok plugin serves on purpose.
     */
    fun doctorLine(
        host: String,
        flag: Kind?,
        row: McpRow,
    ): String? {
        val deferred = row.plugin != null && row.plugin != RTOK.name
        val what =
            when (row.entry) {
                EntryState.Present -> {
                    null
                }

                EntryState.Missing -> {
                    when {
                        deferred -> null
                        row.diff != null -> "missing: ${row.file} (${row.diff})"
                        else -> "missing: ${row.file} has no \"${RTOK.name}\""
                    }
                }

                EntryState.Stale -> {
                    "stale: ${row.file} ${row.diff.orEmpty()}"
                }
            }
        val f = flag?.let { " --${it.word}" }.orEmpty()
        return what?.let { "$it — rtok side: rtok agents install $host$f" }
    }

    /** rtok's entry `<key>.rtok` in a host's strict-JSON [file], started with [command], named `--host <host>`. */
    fun jsonSpec(
        host: String,
        client: Client,
        file: String,
        key: String,
        command: String,
        shape: EntryShape,
    ) = McpSpec(
        host = host,
        client = client,
        configPath = file,
        format = Format.Json,
        keyPath = McpSpec.keyPath(key),
        server = RTOK,
        launch = Launch(command = command, hostArg = true),
        entryShape = shape,
        duplicateName = DuplicateName.ShowsBoth,
        pluginServes = null,
    )

    /** [path] with the home directory spelled `~`. */
    fun tilde(
        path: String,
        home: String = McpPaths.home(),
    ): String {
        val h = home.trimEnd('/')
        return if (h.isNotEmpty() && path.startsWith("$h/")) "~/${path.removePrefix("$h/")}" else path
    }
}

/** What a host's MCP entry runs, and whether that is rtok's own `rtok mcp`. */
object McpArgv {
    /** The argv an entry runs: `command` plus `args`, or one `command` array. */
    fun argvOf(have: JsonElement): List<String> {
        val obj = have as? JsonObject ?: return emptyList()

        fun strs(v: JsonElement?) = (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
        val command = obj["command"]
        return if (command is JsonPrimitive && command.isString) listOf(command.content) + strs(obj["args"]) else strs(command)
    }

    /**
     * Why a host cannot spawn `argv[0]`, null when it can. A GUI host has no shell PATH, so only an absolute path to an
     * existing file counts; a bare name is flagged only while an update can write an absolute one.
     */
    fun unresolvedCommand(
        argv: List<String>,
        mcpCommand: () -> String = ::rtokMcpCommand,
    ): String? {
        val bin = argv.firstOrNull()
        return when {
            bin == null -> null
            bin.startsWith('/') -> if (isFile(bin)) null else "runs `$bin`, which does not exist"
            mcpCommand().startsWith('/') -> "runs bare `$bin`, which a GUI host cannot find without the shell's PATH"
            else -> null
        }
    }

    /** Null when [have] runs `rtok mcp` for [host] (any rtok path, with or without `--host <host>`), else what it runs. */
    fun staleDiff(
        have: JsonElement,
        host: String,
    ): String? {
        val argv = argvOf(have)
        val wantArgs = RTOK.args + listOf("--host", host)
        val ours = argv.isNotEmpty() && isRtokBin(argv[0]) && argv.drop(1).let { it == RTOK.args || it == wantArgs }
        val want = (listOf(RTOK.command) + wantArgs).joinToString(" ")
        return if (ours) null else "runs `${argv.joinToString(" ")}`, want `$want`"
    }
}

/** Whether a host config file holds a server entry (agents/mcp.rs `has_entry`, `has_toml_entry`). */
object McpEntries {
    /** True when [path]'s JSONC parses and holds an object at `<key>.<name>` (dotted [key] walks nested objects). */
    fun hasEntry(
        path: String,
        key: String,
        name: String,
    ) = entryIn(path, Format.Jsonc, McpSpec.keyPath(key), name)

    /** True when [path]'s TOML holds a `[<table>.<name>]` table. */
    fun hasTomlEntry(
        path: String,
        table: String,
        name: String,
    ) = entryIn(path, Format.Toml, McpSpec.keyPath(table), name)

    private fun entryIn(
        path: String,
        format: Format,
        keyPath: List<String>,
        name: String,
    ): Boolean {
        val spec = McpSpec("", Client.Cli, path, format, keyPath, server = RTOK.copy(name = name))
        return runCatching { McpConfig.readEntry(DiskFs, spec) is JsonObject }.getOrDefault(false)
    }
}

/** True for any path to the rtok binary (`rtok`, `/opt/bin/rtok`, `rtok.exe`), or the running executable. */
fun isRtokBin(bin: String): Boolean = bin == rtok.sys.currentExe() || cmdStem(bin).equals("rtok", ignoreCase = true)

/** agents/mod.rs `resolve_mcp_command`: an absolute rtok on PATH, else `~/.ketch/bin/rtok` when installed, else `rtok`. */
fun resolveMcpCommand(
    onPath: String?,
    home: String,
): String {
    val ketch = "$home/.ketch/bin/rtok"
    return onPath?.takeIf { it.startsWith('/') } ?: ketch.takeIf { home.isNotEmpty() && isFile(it) } ?: RTOK.command
}

/** The command a host's MCP entry should run on this machine. */
fun rtokMcpCommand(): String = resolveMcpCommand(HostBins.findOnPath(RTOK.command), McpPaths.home())

/** The read-only disk status goes through. */
object DiskFs : McpFs {
    override fun read(path: String): String? = runCatching { rtok.sys.readToString(path) }.getOrNull()

    override fun write(
        path: String,
        text: String,
    ): Unit = throw IllegalStateException("$path: MCP status is read-only")
}
