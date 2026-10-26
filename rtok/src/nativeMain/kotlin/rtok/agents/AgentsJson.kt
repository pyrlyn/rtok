// Port of src/model/mod.rs `AgentListRow`, `agent_row`, `agents_list` and `agents_listed`: `rtok agents list|info --json`.
// Keys follow the Rust struct's field order, enums are snake_case, printed as serde_json's pretty form (2 spaces).
// `plugin_version`/`plugin_source` come from outdated.rs `plugin_status` and are omitted when unknown, as in Rust.
package rtok.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.config.Config

object AgentsJson {
    val PRETTY =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    /** `rtok agents list --json`: one row per host × variant, in registry order. */
    fun list(
        cfg: Config,
        env: AgentsRun.Env = AgentsRun.Env(),
    ): String = encode(rows(cfg, Hosts.ALL.map { it.id }, env))

    /** `rtok agents info <host> --json`: [list] for the named hosts; an unknown one is refused. */
    fun info(
        cfg: Config,
        hosts: List<String>,
        env: AgentsRun.Env = AgentsRun.Env(),
    ): String = encode(rows(cfg, AgentsRun.resolve(hosts, env.agent).map { it.id }, env))

    fun encode(rows: List<JsonObject>): String = PRETTY.encodeToString(JsonArray.serializer(), JsonArray(rows))

    fun rows(
        cfg: Config,
        ids: List<String>,
        env: AgentsRun.Env = AgentsRun.Env(),
    ): List<JsonObject> =
        ids.flatMap { id ->
            val a = env.agent(id) ?: return@flatMap emptyList()
            AgentsRun.variants(a).map { v -> row(cfg, a, v, env) }
        }

    /** One variant: modules, MCP surfaces and plugins only when the app is present, as in Rust. */
    fun row(
        cfg: Config,
        a: Agent,
        v: Variant,
        env: AgentsRun.Env = AgentsRun.Env(),
        appVersion: (Variant) -> String = { AppVersion.of(cfg, a.id, it) },
        pluginStatus: (Agent, Variant) -> PluginStatus? = { ag, va -> Outdated.pluginStatus(ag, va.kind, cfg) },
    ): JsonObject {
        val present = env.present(cfg, a, v)
        val app = if (present) Locate.locate(cfg, a.id, v) else null
        val modules = if (present) Modules.rows(a, v.kind, cfg) else emptyList()
        val mcp = if (present) McpDoctor.rows(a, cfg, v.kind) else emptyList()
        val plugins = if (present) PluginRows.rows(a, v.kind, cfg, modules) else emptyList()
        val status = if (present) pluginStatus(a, v) else null
        val m =
            linkedMapOf<String, JsonElement>(
                "host" to str(a.id),
                "kind" to str(v.kind.word),
                "name" to str(v.name),
                "present" to JsonPrimitive(present),
                "app" to str(app),
                "version" to str(if (present) appVersion(v) else null),
                "config" to JsonArray(a.files(cfg, v.kind).map(::str)),
                "modules" to JsonArray(modules.map(::module)),
                "mcp" to JsonArray(mcp.map(::mcp)),
                "plugins" to JsonArray(plugins.map(::plugin)),
            )
        status?.version?.let { m["plugin_version"] = str(it) }
        status?.source?.let { m["plugin_source"] = str(it) }
        return JsonObject(m)
    }

    private fun str(s: String?): JsonElement = s?.let(::JsonPrimitive) ?: JsonNull

    /** serde's `rename_all = "snake_case"` of [ModuleState]. */
    fun state(s: ModuleState): String =
        when (s) {
            ModuleState.Installed -> "installed"
            ModuleState.NotInstalled -> "not_installed"
            ModuleState.NotSupported -> "not_supported"
        }

    private fun module(r: ModuleRow) = JsonObject(linkedMapOf("name" to str(r.name), "state" to str(state(r.state)), "note" to str(r.note)))

    private fun plugin(r: PluginRow) = JsonObject(linkedMapOf("id" to str(r.id), "on" to JsonPrimitive(r.on), "state" to str(state(r.state))))

    private fun mcp(r: McpRow): JsonObject {
        val m = linkedMapOf<String, JsonElement>("surface" to str(r.surface), "file" to str(r.file), "entry" to str(r.entry.json))
        r.diff?.let { m["diff"] = str(it) }
        m["plugin"] = str(r.plugin)
        return JsonObject(m)
    }
}
