// The bash script, in clap_complete's shape: walk the words to a command id, then offer that command's words and
// complete a path after an option that takes one.
package rtok.completions

internal object BashScript {
    fun render(root: CompNode): String {
        val nodes = CompTree.walk(root)
        val bin = root.name
        return buildString {
            append("_$bin() {\n    local i cur prev opts cmd\n    COMPREPLY=()\n")
            append("    if [[ \"\${BASH_VERSINFO[0]}\" -ge 4 ]]; then\n        cur=\"\$2\"\n    else\n        cur=\"\${COMP_WORDS[COMP_CWORD]}\"\n    fi\n")
            append("    prev=\"\$3\"\n    cmd=\"\"\n    opts=\"\"\n\n    for i in \"\${COMP_WORDS[@]:0:COMP_CWORD}\"\n    do\n        case \"\${cmd},\${i}\" in\n")
            append("            \",\$1\")\n                cmd=\"$bin\"\n                ;;\n")
            for ((path, n) in nodes) {
                for (c in n.children) append("            ${id(path)},${c.name})\n                cmd=\"${id(path + c.name)}\"\n                ;;\n")
            }
            append("            *)\n                ;;\n        esac\n    done\n\n    case \"\${cmd}\" in\n")
            nodes.forEach { (path, n) -> append(case(path, n)) }
            append("    esac\n}\n\n")
            append("if [[ \"\${BASH_VERSINFO[0]}\" -eq 4 && \"\${BASH_VERSINFO[1]}\" -ge 4 || \"\${BASH_VERSINFO[0]}\" -gt 4 ]]; then\n")
            append("    complete -F _$bin -o nosort -o bashdefault -o default $bin\nelse\n    complete -F _$bin -o bashdefault -o default $bin\nfi\n")
        }
    }

    /** `rtok__subcmd__a__subcmd__b`: clap's id of a nested command. */
    private fun id(path: List<String>) = path.joinToString("__subcmd__") { it.replace('-', '_') }

    private fun case(
        path: List<String>,
        n: CompNode,
    ): String {
        val words = n.opts.flatMap { it.names } + n.positionals.map { "<${it.uppercase()}>" } + n.children.map { it.name }
        return buildString {
            append("        ${id(path)})\n            opts=\"${words.joinToString(" ")}\"\n")
            append("            if [[ \${cur} == -* || \${COMP_CWORD} -eq ${path.size} ]] ; then\n")
            append("                COMPREPLY=( \$(compgen -W \"\${opts}\" -- \"\${cur}\") )\n                return 0\n            fi\n")
            append("            case \"\${prev}\" in\n")
            for (o in n.opts.filter { it.takes }) {
                append("                ${o.names.joinToString("|")})\n                    COMPREPLY=(\$(compgen -f \"\${cur}\"))\n                    return 0\n                    ;;\n")
            }
            append("                *)\n                    COMPREPLY=()\n                    ;;\n            esac\n")
            append("            COMPREPLY=( \$(compgen -W \"\${opts}\" -- \"\${cur}\") )\n            return 0\n            ;;\n")
        }
    }
}
