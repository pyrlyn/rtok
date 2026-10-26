// Shared fixtures for the memory plugin tests.
package rtok.plugins.memory

import kotlinx.serialization.json.*
import rtok.config.Memory
import rtok.plugin.Runtime
import rtok.store.SerdeJson
import rtok.store.insertCallIo
import rtok.support.installDb

/** An in-memory runtime with `[plugins.memory]` tuned by [tune]. */
fun memRt(
    session: String,
    tune: (Memory) -> Memory = { it },
): Runtime =
    installDb().let {
        Runtime.inMemory(session).also { rt -> rt.config = rt.config.copy(plugins = rt.config.plugins.copy(memory = tune(rt.config.plugins.memory))) }
    }

/** A `PreToolUse(<tool>)` row in the hook window the handoff ledger scans, as hook dispatch archives it. */
fun touchAt(
    rt: Runtime,
    cwd: String,
    tool: String,
    input: JsonElement,
) {
    val stdin =
        buildJsonObject {
            put("hook_event_name", "PreToolUse")
            put("cwd", cwd)
            put("tool_name", tool)
            put("tool_input", input)
        }
    val id = rt.recordCall("hook", "hook", "PreToolUse")
    rt.store.insertCallIo(id, SerdeJson.encode(stdin).encodeToByteArray(), null, 65536, null)
}

fun touch(
    rt: Runtime,
    tool: String,
    path: String,
) = touchAt(rt, ".", tool, buildJsonObject { put("file_path", path) })

/** Rust tests flip `cx.config.plugins.memory.*` in place; the Kotlin config is immutable data. */
fun Runtime.mem(tune: (Memory) -> Memory) {
    config = config.copy(plugins = config.plugins.copy(memory = tune(config.plugins.memory)))
}
