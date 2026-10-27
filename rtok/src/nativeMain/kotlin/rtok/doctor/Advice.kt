// Port of the pure checks of src/doctor.rs: `resolve_tool_search` (T388), `anthropic_base` (T173),
// `tools_rewrite_advice` (T59.5), `always_load_advice` (T384), `overlap_lines` (T59.7), `hook_client_advice` (T349)
// and the hook count (`count_hooks`, `add_hooks_object`, `plugin_install_paths`).
package rtok.doctor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import rtok.agents.HostBins
import rtok.plugins.memory.Sync

/** Hook commands counted per event; [prefersClient] = some command names the fast `rtok-hook` client (T349). */
data class HookCount(
    val total: Int = 0,
    val byEvent: Map<String, Int> = emptyMap(),
    val prefersClient: Boolean = false,
) {
    operator fun plus(o: HookCount): HookCount {
        val ev = LinkedHashMap(byEvent)
        for ((k, n) in o.byEvent) ev[k] = (ev[k] ?: 0) + n
        return HookCount(total + o.total, ev.sortedByKey(), prefersClient || o.prefersClient)
    }
}

/** Rust's `BTreeMap` order. */
internal fun <V> Map<String, V>.sortedByKey(): Map<String, V> = entries.sortedBy { it.key }.associate { it.key to it.value }

@Suppress("TooManyFunctions")
internal object Advice {
    /** The std-only fast hook client (T178) that installed hook commands try before `rtok hook`. */
    const val HOOK_CLIENT = "rtok-hook"

    private const val DEFAULT_ANTHROPIC = "https://api.anthropic.com"
    private const val MAX_AUTO_PERCENT = 100

    /** `a/b/c` inside a JSON value, or null. */
    fun pointer(
        v: JsonElement?,
        vararg keys: String,
    ): JsonElement? = keys.fold(v) { acc, k -> (acc as? JsonObject)?.get(k) }

    private fun str(v: JsonElement?) = (v as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun nonempty(s: String?) = s?.takeIf { it.isNotEmpty() }

    /**
     * `ENABLE_TOOL_SEARCH` as Claude Code reads it: `settings.json` `env` wins over the shell, empty is unset. Only
     * `false` is disabled; `true`, `auto` and `auto:N` (N ≤ 100) keep deferral on; any other value is no override.
     * With no override a custom base URL leaves the state unknown.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun resolveToolSearch(
        settings: JsonElement?,
        env: String?,
        customBaseUrl: Boolean,
    ): ToolSearch {
        val fromSettings =
            (pointer(settings, "env", "ENABLE_TOOL_SEARCH") as? JsonPrimitive)
                ?.let { p -> if (p.isString) p.content else p.booleanOrNull?.toString() }
                ?.takeIf { it.isNotEmpty() }
                ?.let { it to "settings.json env" }
        val fromEnv = nonempty(env)?.let { it to "environment" }
        for ((raw, place) in listOfNotNull(fromSettings, fromEnv)) {
            val value = raw.trim().lowercase()
            val state =
                when {
                    value == "false" -> ToolSearchState.Disabled

                    value == "true" || value == "auto" -> ToolSearchState.Enabled

                    value.removePrefix("auto:").let { n ->
                        value.startsWith("auto:") && n.toUByteOrNull()?.let { it.toInt() <= MAX_AUTO_PERCENT } == true
                    } -> ToolSearchState.Enabled

                    else -> continue
                }
            return ToolSearch(state, "ENABLE_TOOL_SEARCH=$value in $place")
        }
        if (customBaseUrl) return ToolSearch(ToolSearchState.Unknown, "heuristic: ANTHROPIC_BASE_URL set")
        return ToolSearch()
    }

    /** The `ANTHROPIC_BASE_URL` a Claude Code session sees: settings `env` over the shell; the default endpoint is unset. */
    fun anthropicBase(
        settings: JsonElement?,
        env: String?,
    ): String? {
        val base = nonempty(str(pointer(settings, "env", "ANTHROPIC_BASE_URL"))) ?: nonempty(env) ?: return null
        return base.takeUnless { isDefaultAnthropicBase(it) }
    }

    /** True for `https://api.anthropic.com` with any trailing slashes (T173). */
    fun isDefaultAnthropicBase(url: String) = url.trim().trimEnd('/') == DEFAULT_ANTHROPIC

    /** Advice for enabling `[proxy.tools_rewrite]` when all four conditions hold. */
    @Suppress("ComplexCondition", "LongParameterList")
    fun toolsRewriteAdvice(
        toolSearchDisabled: Boolean,
        proxy: String,
        rtokPort: Int,
        totalDescTokens: Int,
        toolsRewriteEnabled: Boolean,
        threshold: Int,
    ): String? {
        val inChain = proxy.split('→').any { it == "$rtokPort" || it == "localhost:$rtokPort" }
        return if (toolSearchDisabled && inChain && !toolsRewriteEnabled && totalDescTokens >= threshold) {
            "mcp descriptions ~$totalDescTokens tokens every turn; [proxy.tools_rewrite] enabled = true shortens them"
        } else {
            null
        }
    }

    /** T384: name `alwaysLoad: true` on the rtok MCP entry with its price, unless absent, set, or tool search is off. */
    @Suppress("ReturnCount")
    fun alwaysLoadAdvice(
        entry: JsonElement?,
        toolSearchOff: Boolean,
        rtok: ServerInfo?,
    ): String? {
        val e = entry?.takeIf { (it as? JsonObject)?.get("alwaysLoad") != JsonPrimitive(true) } ?: return null
        val r = rtok?.takeIf { it.tools > 0 } ?: return null
        if (toolSearchOff || e !is JsonObject) return null
        return "native Read denies cost a ToolSearch turn on Claude Code; \"alwaysLoad\": true on the rtok " +
            "MCP entry loads its ${r.tools} tools (~${r.descTokens} tokens) up front instead"
    }

    /** T59.7: the duplicate checks; each names the rtok config key that turns the rtok side off, none claims a saving. */
    fun overlapLines(
        archiveOn: Boolean,
        memoryRecallOn: Boolean,
        syncBlock: Boolean,
        installed: List<String>,
    ): List<String> =
        buildList {
            if ("claude" in installed && memoryRecallOn) {
                add(
                    "duplicate: Claude Code auto-memory (on by default) and memory recall both carry              " +
                        "facts — rtok side: [plugins.memory] enabled = false",
                )
            }
            if (syncBlock && memoryRecallOn) add(Sync.OVERLAP_LINE)
            if (archiveOn && "opencode" in installed) {
                add("duplicate: OpenCode marks old tool outputs natively — rtok side:              [plugins.archive] enabled = false")
            }
            if (archiveOn && "cursor" in installed) {
                add("duplicate: Cursor Dynamic Context prunes old context too — rtok side:              [plugins.archive] enabled = false")
            }
        }

    /** T349: hooks that try `rtok-hook` fall back to the slower `rtok hook` when it is not on [path]. Advice only. */
    fun hookClientAdvice(
        prefersClient: Boolean,
        path: String?,
    ): String? =
        if (prefersClient && HostBins.findBin(HOOK_CLIENT, path) == null) {
            "hook client: `$HOOK_CLIENT` is not on PATH, so hooks fall back to the slower `rtok hook`; " +
                "install it beside `rtok` (a ketch install links both)"
        } else {
            null
        }

    /** The hook commands under a settings file's `hooks` object. */
    fun countHooks(settings: JsonElement?): HookCount = (pointer(settings, "hooks") as? JsonObject)?.let(::hooksObject) ?: HookCount()

    /** Every command under a `hooks` object (`settings.json` or a plugin's `hooks/hooks.json`, same shape). */
    fun hooksObject(hooks: JsonObject): HookCount {
        val byEvent = LinkedHashMap<String, Int>()
        var total = 0
        var client = false
        for ((event, entries) in hooks) {
            val n = (entries as? JsonArray)?.sumOf(::innerHookCount) ?: 0
            total += n
            byEvent[event] = (byEvent[event] ?: 0) + n
            client = client || HOOK_CLIENT in entries.toString()
        }
        return HookCount(total, byEvent.sortedByKey(), client)
    }

    private fun innerHookCount(entry: JsonElement) = (pointer(entry, "hooks") as? JsonArray)?.size ?: 1

    /** Every `installPath` an `installed_plugins.json` lists for [id] (V2 shape). */
    fun pluginInstallPaths(
        root: JsonElement?,
        id: String,
    ): List<String> =
        (pointer(root, "plugins", id) as? JsonArray).orEmpty().mapNotNull { e ->
            ((e as? JsonObject)?.get("installPath") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        }
}
