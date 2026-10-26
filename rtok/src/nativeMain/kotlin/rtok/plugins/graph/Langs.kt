// Port of the language tables in src/plugins/graph/lsp.rs (`LANGS`, `EXTENSIONS`, `language_of`, `server_of`):
// project kinds by marker file, the name `[plugins.graph.backend_by_language]` is keyed by, and the server for it.
package rtok.plugins.graph

import rtok.sys.isFile
import rtok.sys.join

/** A server command: binary and arguments. */
data class LspServer(
    val bin: String,
    val args: List<String>,
)

object Langs {
    private class Lang(
        val marker: String,
        val name: String,
        val server: LspServer?,
    )

    /** Tried in order; `null` server means tags only. */
    private val LANGS =
        listOf(
            Lang("Cargo.toml", "rust", LspServer("rust-analyzer", emptyList())),
            Lang("compile_commands.json", "c", LspServer("clangd", emptyList())),
            Lang("tsconfig.json", "typescript", LspServer("typescript-language-server", listOf("--stdio"))),
            Lang("pubspec.yaml", "dart", LspServer("dart", listOf("language-server"))),
            Lang("go.mod", "go", null),
            Lang("pyproject.toml", "python", null),
            Lang("package.json", "javascript", null),
            // T329.10: neither a server nor a grammar, named so `backend_by_language` can pin them to `text`.
            Lang("pom.xml", "java", null),
            Lang("build.gradle", "java", null),
            Lang("Gemfile", "ruby", null),
            Lang("composer.json", "php", null),
            Lang("mix.exs", "elixir", null),
            Lang("Package.swift", "swift", null),
        )

    /** Extension to language; lists more languages than [LANGS] has markers for. */
    private val EXTENSIONS =
        listOf(
            "rs" to "rust",
            "c" to "c",
            "h" to "c",
            "cc" to "c",
            "cpp" to "c",
            "cxx" to "c",
            "hpp" to "c",
            "ts" to "typescript",
            "tsx" to "typescript",
            "js" to "javascript",
            "mjs" to "javascript",
            "cjs" to "javascript",
            "jsx" to "javascript",
            "dart" to "dart",
            "go" to "go",
            "py" to "python",
            "java" to "java",
            "kt" to "kotlin",
            "rb" to "ruby",
            "php" to "php",
            "ex" to "elixir",
            "exs" to "elixir",
            "swift" to "swift",
            "cs" to "csharp",
        )

    /** The language of the project at [root] by its first marker file, or null. */
    fun languageOf(root: String): String? = LANGS.firstOrNull { isFile(join(root, it.marker)) }?.name

    fun languageOfExt(ext: String): String? = EXTENSIONS.firstOrNull { it.first.equals(ext, ignoreCase = true) }?.second

    /** The server rtok spawns for [lang], if it has one. */
    fun serverOf(lang: String): LspServer? = LANGS.firstOrNull { it.name == lang && it.server != null }?.server

    /** Languages rtok has a server for. */
    fun servedLanguages(): List<String> = LANGS.filter { it.server != null }.map { it.name }
}
