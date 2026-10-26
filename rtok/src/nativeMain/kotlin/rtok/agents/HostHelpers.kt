// Port of the shared host helpers in src/agents/mod.rs: hook command tokens (`shell_quote_bin`, `rtok_command`,
// `rtok_hook_bin`, `hook_resolver`, `unquote_bin`), `strip_ours` reports (`takes_hook`, `removed_report`,
// `with_kept`), host CLI calls (`cli_label`, `run_cli`) and local-path plugin offers (`manifest_names`,
// `PluginOffer`, `offer_plugin`, `print_offer`). Unix only: `rtok_command` is always the bare name here.
package rtok.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agent.sdk.Apply
import rtok.agent.sdk.KETCH_INSTALL
import rtok.agent.sdk.NO_CHANGES
import rtok.agent.sdk.keepEdited
import rtok.config.Config
import rtok.mcp.config.RTOK
import rtok.sys.ProgressRunner
import rtok.sys.join
import rtok.sys.readToString

/** One host CLI call: null when it succeeded, else the failure text. */
typealias CliCall = (List<String>) -> String?

/** Hook command tokens. */
object HookLine {
    /** A binary token for a hook shell line: quoted (cmd.exe style, `""` for `"`) only when it has a space or quote. */
    fun shellQuoteBin(bin: String): String = if (bin.none { it.isWhitespace() || it == '"' }) bin else "\"${bin.replace("\"", "\"\"")}\""

    /** The command written into hook lines; on unix always the bare name (hooks resolve it in a shell). */
    fun rtokCommand(): String = RTOK.command

    /** claude/mod.rs `desktop_command`: the running binary's absolute path, for apps started without a shell PATH. */
    fun desktopCommand(): String = rtok.sys.currentExe() ?: rtokCommand()

    /** Binary token for hook command lines (quoted when needed). */
    fun rtokHookBin(): String = shellQuoteBin(rtokCommand())

    /**
     * POSIX hook line for a bare `rtok` (T174/T250.2): PATH, then ketch's layout, then fail open, printing [note]
     * (the host's session-start JSON, no `'`) if given.
     */
    fun hookResolver(
        args: String,
        note: String? = null,
    ): String {
        require(note == null || '\'' !in note) { note.orEmpty() }
        val tail = note?.let { " && printf '%s' '$it'" }.orEmpty()
        return "command -v rtok >/dev/null 2>&1 && exec rtok $args; " +
            "[ -x \"\$HOME/.ketch/bin/rtok\" ] && exec \"\$HOME/.ketch/bin/rtok\" $args; " +
            "true$tail; exit 0"
    }

    /** Strip one layer of surrounding `"` or `'` from a hook binary token. */
    fun unquoteBin(bin: String): String {
        val b = bin.trim()
        val quoted = b.length >= 2 && b.first() == b.last() && (b.first() == '"' || b.first() == '\'')
        return if (quoted) b.substring(1, b.length - 1) else b
    }
}

/** `strip_ours` reports. */
object StripReport {
    /**
     * Whether a host's `strip_ours` takes an rtok hook it found (T246.3): one as the installer wrote it ([unchanged])
     * goes; a changed one goes only as [keepEdited] decides, its `leave`/`?` line joining [kept].
     */
    fun takesHook(
        apply: Apply,
        unchanged: Boolean,
        at: () -> String,
        kept: MutableList<String>,
    ): Boolean {
        val line = if (unchanged) null else keepEdited(apply, at())
        line?.let { kept += it }
        return line == null
    }

    /** `{n} removed`, or [NO_CHANGES] for none: the tail of a `strip_ours` report. */
    fun removedReport(removed: Int): String = if (removed == 0) NO_CHANGES else "$removed removed"

    /** A `strip_ours` report: the [kept] lines, then [report] unless it is [NO_CHANGES]. */
    fun withKept(
        kept: List<String>,
        report: String,
    ): String {
        val lines = if (report == NO_CHANGES) kept else kept + report
        return if (lines.isEmpty()) NO_CHANGES else lines.joinToString("\n")
    }
}

/** Host CLI calls. */
object HostCli {
    /** What the spinner says while `<bin> <args>` runs: the CLI and its subcommand, not the paths after it. */
    fun cliLabel(
        bin: String,
        args: List<String>,
    ): String = "running $bin ${args.take(2).joinToString(" ")}…"

    private const val CLI_CAP = 64L * 1024

    /**
     * One `<host CLI> <args>` call (the program [Locate.cliProgram] names), optionally with one env override.
     * Null on success, else the first stderr line, or the spawn error.
     */
    fun runCli(
        cfg: Config,
        host: String,
        args: List<String>,
        env: Pair<String, String>? = null,
    ): String? {
        val program = Locate.cliProgram(cfg, host) ?: return "$host: not found"
        val run = ProgressRunner(cliLabel(host, args), program, args).also { r -> env?.let { r.env(it.first, it.second) } }
        return runCatching { run.runCapped(CLI_CAP).first }.fold(
            onSuccess = { out ->
                if (out.exitCode == 0) {
                    null
                } else {
                    out.stderr
                        .decodeToString()
                        .lineSequence()
                        .first()
                        .ifEmpty { "non-zero exit" }
                }
            },
            onFailure = { "$host: ${it.message}" },
        )
    }
}

/** Local-path plugin offers through a host's own plugin store. */
object PluginOffers {
    /** True when `dir/<manifest>` is JSON naming [name] in a top-level `"name"`: the host's own plugin-store marker (D21). */
    fun manifestNames(
        dir: String,
        manifest: String,
        name: String,
    ): Boolean {
        val root = runCatching { Json.parseToJsonElement(readToString(join(dir, manifest))) }.getOrNull() as? JsonObject
        return (root?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content == name
    }

    /**
     * Offer, install, or uninstall the plugin tree at [PluginSource.of] through a host CLI's local-path verbs (D21,
     * `--yes`). [installed] is the caller's marker read. Dry-run writes nothing; a failed [call] folds into
     * "offer … (`<bin>` failed: …)" instead of failing the whole apply.
     */
    fun offerPlugin(
        cfg: Config,
        remove: Boolean,
        installed: Boolean,
        offer: PluginOffer,
        plugins: PluginSource = ShippedPlugins,
        call: CliCall,
    ): String {
        val a = applyOf(cfg)
        val skip = if (remove) !installed else installed || !a.yes
        val args = if (remove) offer.uninstallVerb + offer.name else offer.installVerb + plugins.of(offer.srcRel)
        val shown = "${offer.bin} ${args.joinToString(" ")}"
        if (skip || a.dryRun) return dryOrSkip(skip, remove, offer, shown)
        return when (val err = call(args)) {
            null -> if (remove) "- plugin ${offer.name}" else "+ plugin ${offer.srcRel} → ${offer.name}"
            else -> "offer ${offer.srcRel} → $shown (${offer.bin} failed: $err)"
        }
    }

    private fun dryOrSkip(
        skip: Boolean,
        remove: Boolean,
        offer: PluginOffer,
        shown: String,
    ) = when {
        skip -> NO_CHANGES
        remove -> "- plugin ${offer.name} ($shown)"
        else -> "offer ${offer.srcRel} → $shown $KETCH_INSTALL"
    }

    /**
     * A plugin whose store only the host writes (T86): print [install] behind `--yes` and never run it. Remove leaves a
     * staged copy alone and says so with [keep].
     */
    fun printOffer(
        cfg: Config,
        remove: Boolean,
        installed: Boolean,
        srcRel: String,
        install: String,
        keep: String,
    ): String {
        val a = applyOf(cfg)
        if (remove && !a.dryRun) return if (installed) keep else NO_CHANGES
        return if (a.yes) "offer $srcRel → $install $KETCH_INSTALL" else NO_CHANGES
    }
}

/** A host's local-path plugin verbs (Copilot `plugin install <path>`, Gemini `extensions link <path>`, …). */
data class PluginOffer(
    val bin: String,
    val name: String,
    val srcRel: String,
    val installVerb: List<String>,
    val uninstallVerb: List<String>,
)
