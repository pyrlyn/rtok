// Port of src/agents/proxy_env.rs: `env.<KEY>` pointed at rtok's proxy in a host's JSON settings (Claude's
// `ANTHROPIC_BASE_URL`, OpenCode's `OPENAI_BASE_URL`). The report names how to revert; strip leaves a foreign URL.
package rtok.agents

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agent.sdk.HostJson
import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config

object ProxyEnv {
    private const val ENV = "env"

    /** `http://<bind>:<port>` (agents/mod.rs `anthropic_proxy_url`). */
    fun anthropic(cfg: Config) = "http://${cfg.proxy.bind}:${cfg.proxy.port}"

    /**
     * Set or drop `env.[key]` = [url] in [path]. With [onlyOurs], remove takes the key only while it still equals [url]
     * (Claude: a URL the user set is not ours to delete).
     */
    fun run(
        cfg: Config,
        path: String,
        key: String,
        url: String,
        remove: Boolean,
        onlyOurs: Boolean = false,
    ): String =
        HostJson.edit(applyOf(cfg), path) { root ->
            if (remove) strip(root, key, url.takeIf { onlyOurs }) else insert(root, key, url)
        }

    /** The string at `env.[key]`, or null. */
    fun value(
        root: JsonObject,
        key: String,
    ): String? = str(root[ENV], key)

    private fun insert(
        root: JsonObject,
        key: String,
        url: String,
    ): Pair<JsonObject, String> {
        val env = root[ENV] as? JsonObject ?: JsonObject(emptyMap())
        val prev = env[key]
        if (prev == JsonPrimitive(url)) return root to NO_CHANGES
        val revert = (prev as? JsonPrimitive)?.takeIf { it.isString }?.let { "revert: set env.$key to ${it.content}" } ?: "revert: remove env.$key"
        return JsonObject(root + (ENV to JsonObject(env + (key to JsonPrimitive(url))))) to "env.$key: $url\n$revert"
    }

    private fun strip(
        root: JsonObject,
        key: String,
        only: String?,
    ): Pair<JsonObject, String> {
        val env = root[ENV] as? JsonObject
        if (env == null || !ours(env, key, only)) return root to NO_CHANGES
        val left = JsonObject(env - key)
        return JsonObject(if (left.isEmpty()) root - ENV else root + (ENV to left)) to "- env.$key"
    }

    private fun ours(
        env: JsonObject,
        key: String,
        only: String?,
    ) = key in env && (only == null || str(env, key) == only)
}
