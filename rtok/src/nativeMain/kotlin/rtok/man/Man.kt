// Port of src/man.rs (T316): man pages from the command tree, `rtok.1` plus one page per subcommand
// (`rtok-agents.1`, `rtok-agents-install.1`, …), each with a `SEE ALSO` to its parent and children. The sections
// follow clap_mangen's (NAME, SYNOPSIS, DESCRIPTION, OPTIONS, SUBCOMMANDS, VERSION); the roff is this file's own.
package rtok.man

import rtok.cli.CommandSpec
import rtok.cli.OptSpec

object Man {
    /** Every page into [dir] (created if missing); the paths written, parent first. */
    fun writeAll(
        root: CommandSpec,
        dir: String,
        version: String,
    ): List<String> {
        rtok.sys.createDirAll(dir)
        val out = mutableListOf<String>()

        fun walk(
            cmd: CommandSpec,
            page: String,
            parent: String?,
            inherited: List<OptSpec>,
        ) {
            val path = "$dir/$page.1"
            rtok.sys.writeString(path, render(cmd, page, parent, inherited, version, isRoot = parent == null))
            out += path
            val globals = inherited + cmd.options.filter { it.global }
            cmd.subcommands.forEach { walk(it, "$page-${it.name}", page, globals) }
        }
        walk(root, root.name, null, emptyList())
        return out
    }

    /** `rtok.1` alone, as `rtok man` prints it: the page [writeAll] writes first. */
    fun print(
        root: CommandSpec,
        version: String,
    ): String = render(root, root.name, null, emptyList(), version, isRoot = true)

    private fun render(
        cmd: CommandSpec,
        page: String,
        parent: String?,
        inherited: List<OptSpec>,
        version: String,
        isRoot: Boolean,
    ): String {
        val source = "${rootName(page)} $version".trim()
        val about =
            cmd.help
                .lineSequence()
                .firstOrNull()
                .orEmpty()
        val opts = cmd.options + inherited.filter { g -> cmd.options.none { it.name == g.name } }
        return buildString {
            append(".ie \\n(.g .ds Aq \\(aq\n.el .ds Aq '\n")
            append(".TH $page 1  \"$page\" \"${esc(source)}\"\n")
            append(".SH NAME\n$page${if (about.isEmpty()) "" else " \\- ${esc(about)}"}\n")
            append(".SH SYNOPSIS\n").append(synopsis(cmd, page, opts, isRoot)).append('\n')
            if (cmd.help.isNotEmpty()) append(".SH DESCRIPTION\n${esc(cmd.help)}\n")
            append(".SH OPTIONS\n")
            opts.forEach { append(".TP\n${optHead(it)}\n").append(if (it.help.isEmpty()) "" else "${esc(it.help)}\n") }
            append(".TP\n\\fB\\-h\\fR, \\fB\\-\\-help\\fR\nPrint help\n")
            if (isRoot) append(".TP\n\\fB\\-V\\fR, \\fB\\-\\-version\\fR\nPrint version\n")
            if (cmd.subcommands.isNotEmpty()) {
                append(".SH SUBCOMMANDS\n")
                cmd.subcommands.forEach { s ->
                    append(
                        ".TP\n$page\\-${esc(s.name)}(1)\n${esc(
                            s.help
                                .lineSequence()
                                .firstOrNull()
                                .orEmpty(),
                        )}\n",
                    )
                }
            }
            if (isRoot && version.isNotEmpty()) append(".SH VERSION\nv${esc(version)}\n")
            val refs = listOfNotNull(parent) + cmd.subcommands.map { "$page-${it.name}" }
            if (refs.isNotEmpty()) append(".SH \"SEE ALSO\"\n${refs.joinToString(",\n") { "\\fB${esc(it)}\\fR(1)" }}\n")
        }
    }

    private fun rootName(page: String) = page.substringBefore('-')

    private fun synopsis(
        cmd: CommandSpec,
        page: String,
        opts: List<OptSpec>,
        isRoot: Boolean,
    ): String {
        val words = mutableListOf("\\fB${esc(page.replace('-', ' '))}\\fR")
        opts.forEach { o -> words += "[${optHead(o)}]" }
        words += "[\\fB\\-h\\fR|\\fB\\-\\-help\\fR]"
        if (isRoot) words += "[\\fB\\-V\\fR|\\fB\\-\\-version\\fR]"
        cmd.args.forEach { a -> words += if (a.required) "<\\fI${esc(a.name.uppercase())}\\fR>" else "[\\fI${esc(a.name.uppercase())}\\fR]" }
        if (cmd.subcommands.isNotEmpty()) words += "<\\fIsubcommands\\fR>"
        return words.joinToString(" ")
    }

    private fun optHead(o: OptSpec): String {
        val short = (o as? OptSpec.Value)?.short?.let { "\\fB\\-$it\\fR, " } ?: ""
        val value = (o as? OptSpec.Value)?.let { " \\fI<${esc(it.metavar)}>\\fR" } ?: ""
        return "$short\\fB\\-\\-${esc(o.name)}\\fR$value"
    }

    /** roff text: backslashes doubled, `-` as `\-`, a leading `.` or `'` guarded so no line reads as a request. */
    fun esc(s: String): String =
        s
            .replace("\\", "\\\\")
            .replace("-", "\\-")
            .lines()
            .joinToString("\n") { if (it.startsWith('.') || it.startsWith('\'')) "\\&$it" else it }
}
