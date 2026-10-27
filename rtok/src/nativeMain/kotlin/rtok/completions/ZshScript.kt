// The zsh script: one `_arguments` function per command, dispatching on the first word to the child's function.
package rtok.completions

internal object ZshScript {
    fun render(root: CompNode): String {
        val bin = root.name
        return buildString {
            append("#compdef $bin\n\nautoload -U is-at-least\n\n")
            for ((path, n) in CompTree.walk(root)) append(function(path, n))
            append("if [ \"\$funcstack[1]\" = \"_$bin\" ]; then\n    _$bin \"\$@\"\nelse\n    compdef _$bin $bin\nfi\n")
        }
    }

    private fun fn(path: List<String>) = "_" + path.joinToString("__") { it.replace('-', '_') }

    private fun function(
        path: List<String>,
        n: CompNode,
    ): String =
        buildString {
            append("${fn(path)}() {\n    local context curcontext=\"\$curcontext\" state line\n    _arguments -s -S -C \\\n")
            for (o in n.opts) {
                val value =
                    if (!o.takes) {
                        ""
                    } else if (o.path) {
                        ":${o.long.uppercase()}:_files"
                    } else {
                        ":${o.long.uppercase()}:_default"
                    }
                val spec = if (o.takes) "=" else ""
                o.names.forEach { name -> append("'$name$spec[${esc(o.help)}]$value' \\\n") }
            }
            n.positionals.forEach { append("'::${it.replace('-', '_')}:_default' \\\n") }
            if (n.children.isEmpty()) {
                append("&& ret=0\n}\n\n")
                return@buildString
            }
            append("\":: :${fn(path)}_commands\" \\\n\"*::: :->${path.last()}\" \\\n&& ret=0\n")
            append("    case \$state in\n    (${path.last()})\n        words=(\$line[1] \"\${words[@]}\")\n        (( CURRENT += 1 ))\n")
            append("        case \$line[1] in\n")
            n.children.forEach { append("            (${it.name})\n                ${fn(path + it.name)}\n                ;;\n") }
            append("        esac\n        ;;\n    esac\n}\n\n")
            append("${fn(path)}_commands() {\n    local commands; commands=(\n")
            n.children.forEach { append("'${it.name.replace(":", "\\:")}:${esc(it.about)}' \\\n") }
            append("    )\n    _describe -t commands '${path.joinToString(" ")} commands' commands \"\$@\"\n}\n\n")
        }

    /** Inside `'…[help]…'`: `'` closed and reopened, brackets and colons escaped. */
    private fun esc(s: String) =
        Completions
            .firstLine(s)
            .replace("'", "'\\''")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace(":", "\\:")
}
