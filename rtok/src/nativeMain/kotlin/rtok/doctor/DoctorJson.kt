// `rtok doctor --json`: the serde shape of src/doctor.rs `Report` (snake_case keys in field order, the same skipped
// empty fields), built by hand so the skip rules stay in one place.
package rtok.doctor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import rtok.agents.ModuleRow
import rtok.agents.ModuleState
import rtok.plugins.graph.Alert
import rtok.plugins.graph.Weak

object DoctorJson {
    private val JSON = Json { encodeDefaults = true }

    private fun strs(l: List<String>) = JsonArray(l.map(::JsonPrimitive))

    private fun pair(
        a: String,
        b: JsonElement,
    ) = JsonArray(listOf(JsonPrimitive(a), b))

    private fun JsonObjectBuilder.list(
        key: String,
        items: List<JsonElement>,
    ) {
        if (items.isNotEmpty()) put(key, JsonArray(items))
    }

    fun of(r: Report): JsonObject =
        buildJsonObject {
            put("hooks_total", r.hooksTotal)
            put("hooks_by_event", JsonObject(r.hooksByEvent.mapValues { JsonPrimitive(it.value) }))
            putJsonArray("mcp") {
                for (s in r.mcp) {
                    add(
                        buildJsonObject {
                            put("name", s.name)
                            put("cmd", s.cmd)
                            put("tools", s.tools)
                            put("desc_tokens", s.descTokens)
                        },
                    )
                }
            }
            put("proxy", r.proxy)
            put("proxy_openai", r.proxyOpenai)
            put("mcp_tool_search_disabled", r.mcpToolSearchDisabled)
            put(
                "mcp_tool_search",
                buildJsonObject {
                    put("state", r.mcpToolSearch.state.word)
                    r.mcpToolSearch.source?.let { put("source", it) }
                },
            )
            put("bash_max_output_length", r.bashMaxOutputLength)
            put("auto_compact_window", r.autoCompactWindow)
            r.readShare?.let { s ->
                put(
                    "read_share",
                    buildJsonObject {
                        put("read_tokens", s.readTokens)
                        put("grep_tokens", s.grepTokens)
                        put("glob_tokens", s.globTokens)
                        put("share", s.share)
                    },
                )
            }
            put("instructions", r.instructions?.let(::instructions) ?: JsonNull)
            r.skills?.let { put("skills", skills(it)) }
            list("overlaps", r.overlaps.map(::JsonPrimitive))
            r.toolsRewriteAdvice?.let { put("tools_rewrite_advice", it) }
            putJsonArray("agents") {
                for (a in r.agents) {
                    add(
                        buildJsonObject {
                            put("host", a.host)
                            put("kind", a.kind)
                            put("modules", JsonArray(a.modules.map(::module)))
                        },
                    )
                }
            }
            list("problems", r.problems.map(::problem))
            list("graph_alerts", r.graphAlerts.map { JSON.encodeToJsonElement(Alert.serializer(), it) })
            list("graph_health", r.graphHealth.map { JSON.encodeToJsonElement(Weak.serializer(), it) })
        }

    private fun instructions(i: Instructions) =
        buildJsonObject {
            put(
                "rows",
                buildJsonArray {
                    for (r in i.rows) {
                        add(
                            buildJsonObject {
                                put("name", r.name)
                                put("tokens", r.tokens)
                                put("path", r.path)
                                put("warn", r.warn)
                            },
                        )
                    }
                },
            )
            put("duplicates", JsonArray(i.duplicates.map { (s, n) -> pair(s, strs(n)) }))
        }

    private fun skills(s: SkillsAudit) =
        buildJsonObject {
            put(
                "rows",
                JsonArray(
                    s.rows.map { r ->
                        buildJsonObject {
                            put("name", r.name)
                            put("source", r.source)
                            put("desc_chars", r.descChars)
                            put("body_bytes", r.bodyBytes)
                            r.invocations?.let { put("invocations", it) }
                            put("warn_desc", r.warnDesc)
                            put("warn_body", r.warnBody)
                            put("warn_never", r.warnNever)
                        }
                    },
                ),
            )
            put("desc_bytes", s.descBytes)
            list(
                "duplicates",
                s.duplicates.map { d ->
                    buildJsonObject {
                        put("name", d.name)
                        put("copies", JsonArray(d.copies.map { (a, b) -> pair(a, JsonPrimitive(b)) }))
                        put("identical", d.identical)
                        put("extra_tokens", d.extraTokens)
                    }
                },
            )
            list(
                "repeats",
                s.repeats.map { r ->
                    buildJsonObject {
                        put("name", r.name)
                        put("loads", r.loads)
                        put("tokens", r.tokens)
                    }
                },
            )
        }

    private fun module(m: ModuleRow) =
        buildJsonObject {
            put("name", m.name)
            put(
                "state",
                when (m.state) {
                    ModuleState.Installed -> "installed"
                    ModuleState.NotInstalled -> "not_installed"
                    ModuleState.NotSupported -> "not_supported"
                },
            )
            put("note", m.note)
        }

    /** hooks.rs `Problem`: empty `event`/`command`, null `matcher`/`group` and false `keep` are left out. */
    fun problem(p: Problem) =
        buildJsonObject {
            put("kind", p.kind)
            put("agent", p.agent)
            put("source", p.source)
            put("path", p.path)
            if (p.event.isNotEmpty()) put("event", p.event)
            p.matcher?.let { put("matcher", it) }
            if (p.command.isNotEmpty()) put("command", p.command)
            put("detail", p.detail)
            put("fixable", p.fixable)
            p.group?.let { put("group", it) }
            if (p.keep) put("keep", true)
        }
}
