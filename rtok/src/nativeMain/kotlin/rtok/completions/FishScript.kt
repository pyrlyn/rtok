// The fish script: one `complete` line per subcommand and option, conditioned on the words seen so far.
package rtok.completions

internal object FishScript {
    fun render(root: CompNode): String {
        val bin = root.name
        return buildString {
            append("# No subcommand typed yet.\n")
            append("function __fish_${bin}_needs_command\n    set -l cmd (commandline -opc)\n    set -e cmd[1]\n    not set -q cmd[1]\nend\n\n")
            append("function __fish_${bin}_using_subcommand\n    set -l cmd (commandline -opc)\n    set -e cmd[1]\n")
            append("    string match -q -- \"\$argv\" (string join ' ' \$cmd[1..(count \$argv)])\nend\n\n")
            for ((path, n) in CompTree.walk(root)) {
                val cond = if (path.size == 1) "__fish_${bin}_needs_command" else "__fish_${bin}_using_subcommand ${path.drop(1).joinToString(" ")}"
                for (o in n.opts) {
                    val short = o.short?.let { " -s $it" } ?: ""
                    val value =
                        if (!o.takes) {
                            ""
                        } else if (o.path) {
                            " -r -F"
                        } else {
                            " -r"
                        }
                    append("complete -c $bin -n \"$cond\"$short -l ${o.long} -d ${quote(o.help)}$value\n")
                }
                for (c in n.children) append("complete -c $bin -n \"$cond\" -f -a \"${c.name}\" -d ${quote(c.about)}\n")
            }
        }
    }

    private fun quote(s: String) = "'" + Completions.firstLine(s).replace("\\", "\\\\").replace("'", "\\'") + "'"
}
