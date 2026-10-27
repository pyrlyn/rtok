// The PowerShell script: a native argument completer that joins the typed words with `;` and switches on them.
package rtok.completions

internal object PowershellScript {
    fun render(root: CompNode): String {
        val bin = root.name
        return buildString {
            append("\nusing namespace System.Management.Automation\nusing namespace System.Management.Automation.Language\n\n")
            append("Register-ArgumentCompleter -Native -CommandName '$bin' -ScriptBlock {\n    param(\$wordToComplete, \$commandAst, \$cursorPosition)\n\n")
            append("    \$commandElements = \$commandAst.CommandElements\n    \$command = @(\n        '$bin'\n")
            append("        for (\$i = 1; \$i -lt \$commandElements.Count; \$i++) {\n            \$element = \$commandElements[\$i]\n")
            append("            if (\$element -isnot [StringConstantExpressionAst] -or\n                \$element.StringConstantType -ne [StringConstantType]::BareWord -or\n")
            append("                \$element.Value.StartsWith('-') -or\n                \$element.Value -eq \$wordToComplete) {\n                break\n        }\n        \$element.Value\n    }) -join ';'\n\n")
            append("    \$completions = @(switch (\$command) {\n")
            for ((path, n) in CompTree.walk(root)) {
                append("        '${path.joinToString(";")}' {\n")
                for (o in n.opts) {
                    o.names.forEach { name ->
                        append("            [CompletionResult]::new('$name', '$name', [CompletionResultType]::ParameterName, ${q(o.help)})\n")
                    }
                }
                n.children.forEach { append("            [CompletionResult]::new('${it.name}', '${it.name}', [CompletionResultType]::ParameterValue, ${q(it.about)})\n") }
                append("            break\n        }\n")
            }
            append("    })\n\n    \$completions.Where{ \$_.CompletionText -like \"\$wordToComplete*\" } |\n        Sort-Object -Property ListItemText\n}\n")
        }
    }

    private fun q(s: String) = "'" + Completions.firstLine(s).replace("'", "''") + "'"
}
