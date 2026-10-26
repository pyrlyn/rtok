// Port of src/agents/claude/migrate.rs: `rtok agents install claude --replace --yes` drops legacy token hooks (rtk,
// lean-ctx, caveman, token-optimizer), retargets a legacy loopback proxy (8788/8787) to rtok's, and disables the
// lean-ctx and code-review-graph MCP servers; rtok's own and unrelated hooks stay.
package rtok.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agent.sdk.HostJson
import rtok.config.Config
import rtok.sys.exists

object ClaudeMigrate {
    private val LEGACY = listOf("rtk hook", "lean-ctx hook", "caveman-proxy", "caveman shrink-hook", "python-launcher.sh")
    private val LEGACY_PORTS = setOf(8788, 8787)
    private val DROP_MCP = listOf("lean-ctx", "code-review-graph")
    private const val ENV_KEY = "ANTHROPIC_BASE_URL"

    fun run(cfg: Config): String {
        require(cfg.setup.dryRun || cfg.setup.yes) { "--replace requires --yes (or --dry-run)" }
        val path = cfg.setup.claude.settingsPath
        val (root, lines) = rewrite(HostJson.read(path), ProxyEnv.anthropic(cfg))
        val claudeJson = cfg.doctor.claudeJson
        val diff =
            if (exists(claudeJson) && claudeJson != path) {
                val (mcpRoot, mcp) = stripMcp(HostJson.read(claudeJson))
                if (mcp != null) HostJson.write(applyOf(cfg), claudeJson, mcpRoot, mcp)
                listOfNotNull(lines, mcp).joinToString("\n")
            } else {
                lines
            }
        HostJson.write(applyOf(cfg), path, root, diff)
        return diff
    }

    /** Strip legacy hooks, retarget the proxy, drop legacy MCP; the report ends with the rtok hooks left. */
    fun rewrite(
        root: JsonObject,
        proxyUrl: String,
    ): Pair<JsonObject, String> {
        val (noHooks, removed) = stripHooks(root)
        val (retargeted, proxy) = retarget(noHooks, proxyUrl)
        val (out, mcp) = stripMcp(retargeted)
        val lines = removed + listOfNotNull(proxy, mcp) + "remaining rtok hooks: ${countRtok(out)}"
        return out to lines.joinToString("\n")
    }

    private fun legacy(cmd: String?) = cmd != null && LEGACY.any { it in cmd }

    private fun stripHooks(root: JsonObject): Pair<JsonObject, List<String>> {
        val hooks = root["hooks"] as? JsonObject ?: return root to emptyList()
        val removed = mutableListOf<String>()
        val next =
            hooks.mapValues { (_, arr) ->
                if (arr !is JsonArray) return@mapValues arr
                JsonArray(arr.mapNotNull { entry -> stripEntry(entry, removed) })
            }
        return JsonObject(root + ("hooks" to JsonObject(next))) to removed
    }

    private fun stripEntry(
        entry: JsonElement,
        removed: MutableList<String>,
    ): JsonElement? {
        val inner = innerHooks(entry) ?: return entry.takeUnless { legacy(str(entry, "command")) }
        val kept =
            inner.filter { h ->
                val cmd = str(h, "command").orEmpty()
                !legacy(cmd).also { if (it) removed += "- $cmd" }
            }
        return if (kept.isEmpty()) null else withInner(entry, kept)
    }

    /** A legacy loopback `env.ANTHROPIC_BASE_URL` → [proxyUrl] plus the old path/query; null when not legacy. */
    fun retarget(
        root: JsonObject,
        proxyUrl: String,
    ): Pair<JsonObject, String?> {
        val url = ProxyEnv.value(root, ENV_KEY)
        val port = url?.let(ProxyUrl::loopbackPort)
        if (url == null || port !in LEGACY_PORTS) return root to null
        val after = url.substringAfter("://")
        val cut = after.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val next = proxyUrl + if (cut < 0) "" else after.substring(cut)
        val env = root["env"] as JsonObject
        return JsonObject(root + ("env" to JsonObject(env + (ENV_KEY to JsonPrimitive(next))))) to "$ENV_KEY $url -> $next"
    }

    private fun stripMcp(root: JsonObject): Pair<JsonObject, String?> {
        val servers = root["mcpServers"] as? JsonObject
        val dropped = DROP_MCP.filter { servers?.containsKey(it) == true }
        if (servers == null || dropped.isEmpty()) return root to null
        return JsonObject(root + ("mcpServers" to JsonObject(servers - dropped.toSet()))) to "disabled MCP: ${dropped.joinToString(", ")}"
    }

    fun countRtok(root: JsonObject): Int =
        (root["hooks"] as? JsonObject)
            ?.values
            .orEmpty()
            .filterIsInstance<JsonArray>()
            .flatten()
            .flatMap { innerHooks(it).orEmpty() }
            .count { "rtok hook" in str(it, "command").orEmpty() }
}
