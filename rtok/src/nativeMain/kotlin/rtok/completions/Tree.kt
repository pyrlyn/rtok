// The shell-completion view of the command tree (what clap_complete reads from clap's `Command`): every command with
// its options, clap's implicit `--help`/`-h` (and `--version`/`-V` on the root), inherited global options, and the
// implicit `help` subcommand of every command that has children.
package rtok.completions

import rtok.cli.CommandSpec
import rtok.cli.OptSpec

/** One option as a completer sees it: names, help, whether it takes a value, and whether that value is a path. */
data class CompOpt(
    val long: String,
    val short: Char?,
    val help: String,
    val takes: Boolean,
    val path: Boolean,
) {
    /** `--long` then `-s`, the order clap lists them. */
    val names: List<String> get() = listOf("--$long") + listOfNotNull(short?.let { "-$it" })
}

/** One command: name, about, options (own, then inherited globals, then help/version), positionals, children. */
data class CompNode(
    val name: String,
    val about: String,
    val opts: List<CompOpt>,
    val positionals: List<String>,
    val children: List<CompNode>,
)

object CompTree {
    private val PATH_METAVARS = setOf("PATH", "FILE", "DIR")
    private val HELP = CompOpt("help", 'h', "Print help", takes = false, path = false)
    private val VERSION = CompOpt("version", 'V', "Print version", takes = false, path = false)
    private const val HELP_ABOUT = "Print this message or the help of the given subcommand(s)"

    /** The tree under [root], the way `rtok --help` presents it. */
    fun of(root: CommandSpec): CompNode = node(root, emptyList(), isRoot = true)

    private fun node(
        spec: CommandSpec,
        inherited: List<OptSpec>,
        isRoot: Boolean,
    ): CompNode {
        val globals = (inherited + spec.options.filter { it.global }).distinctBy { it.name }
        val own = spec.options.filterNot { it.global }
        val opts = (own + globals).map(::opt) + HELP + listOfNotNull(VERSION.takeIf { isRoot })
        val kids = spec.subcommands.map { node(it, globals, isRoot = false) }
        val children = if (kids.isEmpty()) kids else kids + helpNode(kids)
        return CompNode(spec.name, spec.help.lineSequence().first(), opts, spec.args.map { it.name }, children)
    }

    /** clap's implicit `help` subcommand: the sibling names, each without options. */
    private fun helpNode(siblings: List<CompNode>): CompNode {
        fun bare(n: CompNode): CompNode = CompNode(n.name, n.about, emptyList(), emptyList(), n.children.filter { it.name != "help" }.map(::bare))
        val names = siblings.map(::bare)
        return CompNode("help", HELP_ABOUT, emptyList(), emptyList(), names + CompNode("help", HELP_ABOUT, emptyList(), emptyList(), emptyList()))
    }

    private fun opt(o: OptSpec): CompOpt =
        when (o) {
            is OptSpec.Flag -> CompOpt(o.name, null, o.help, takes = false, path = false)
            is OptSpec.Value -> CompOpt(o.name, o.short, o.help, takes = true, path = o.metavar in PATH_METAVARS)
        }

    /** Every command with its path from the root (the root first), depth first. */
    fun walk(root: CompNode): List<Pair<List<String>, CompNode>> {
        val out = mutableListOf<Pair<List<String>, CompNode>>()

        fun go(
            path: List<String>,
            n: CompNode,
        ) {
            out += path to n
            n.children.forEach { go(path + it.name, it) }
        }
        go(listOf(root.name), root)
        return out
    }
}
