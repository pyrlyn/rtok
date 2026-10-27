package rtok.demon

/**
 * The only services a supervisor may run: rtok's own long-running surfaces (Rust `demon::Service`, T20.1). Neither an
 * argument nor a config file can turn `start` into "run this command".
 */
enum class Service(
    val id: String,
    /** What the supervisor runs: the subcommand, plus `--serve` for the resident hook (D32). */
    val args: List<String>,
) {
    PROXY("proxy", listOf("proxy")),
    MCP("mcp", listOf("mcp")),
    WEB("web", listOf("web")),
    HOOK("hook", listOf("hook", "--serve")),
    ;

    override fun toString() = id

    companion object {
        val names: List<String> get() = entries.map { it.id }

        /** Case-insensitive lookup; null for anything else. */
        fun of(name: String): Service? = entries.firstOrNull { it.id.equals(name, ignoreCase = true) }

        /** A name out of `[demon] services`; the error names the section the bad value came from. */
        fun parse(name: String): Service =
            of(name) ?: throw DemonException(
                "[demon] services: invalid value '$name' [possible values: ${names.joinToString(", ")}]",
            )
    }
}

/** A demon verb that cannot go on. */
class DemonException(
    message: String,
) : Exception(message)
