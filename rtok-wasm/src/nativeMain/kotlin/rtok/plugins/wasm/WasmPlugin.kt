// Port of src/plugins/wasm.rs — the out-of-tree `.wasm` plugin host (P32). Loaded only when
// `[plugins.wasm] enabled = true`. The engine is anything behind wasm.WasmRuntime (kotlin-wasm).
package rtok.plugins.wasm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import rtok.json.StrictJson
import rtok.log.FileLog
import rtok.log.appendSplit
import rtok.log.errorPath
import rtok.plugin.sdk.Class
import rtok.plugin.sdk.Ctx
import rtok.plugin.sdk.DashboardPage
import rtok.plugin.sdk.Manifest
import rtok.plugin.sdk.Measurement
import rtok.plugin.sdk.Plugin
import rtok.plugin.sdk.Surface
import rtok.plugin.sdk.ToolDef
import wasm.HostFunction
import wasm.WasmException
import wasm.WasmInstance
import wasm.WasmMemory
import wasm.WasmRuntime
import wasm.WasmType.I32
import wasm.WasmValue
import wasm.readPacked

/**
 * Fuel per guest call (about one unit per call or loop turn). Without a budget a guest `loop`
 * never returns and wedges the synchronous `rtok mcp` loop for every tool, not just the plugin's own.
 */
const val FUEL: Long = 100_000_000

/** What the host functions need from rtok: the token estimator, the file log and stderr. */
class WasmHostEnv(
    val estimate: (String, Class) -> Int,
    val log: FileLog,
    val stderr: (String) -> Unit,
)

/**
 * Appends a [WasmPlugin] for every `*.wasm` in [dir] (unsorted, like `read_dir`). A file that fails
 * to load is reported through [onError] and skipped; an unreadable [dir] adds nothing.
 */
fun appendWasm(
    plugins: List<Pair<Plugin, Boolean>>,
    dir: String,
    runtime: WasmRuntime,
    env: WasmHostEnv,
    isOn: (id: String, defaultOn: Boolean) -> Boolean,
    onError: (String) -> Unit,
): List<Pair<Plugin, Boolean>> {
    val names = runCatching { rtok.sys.listDir(dir) }.getOrElse { return plugins }
    val out = plugins.toMutableList()
    for (name in names) {
        if (!name.endsWith(".wasm")) continue
        val path = "$dir/$name"
        try {
            val p = WasmPlugin.load(path, runtime, env)
            val m = p.manifest()
            out += p to isOn(m.id, m.defaultOn)
        } catch (e: WasmException) {
            onError("$path: ${e.message}")
        }
    }
    return out
}

@Serializable private class WasmManifest(
    val id: String,
    val surfaces: List<String>,
    @SerialName("default_on") val defaultOn: Boolean,
    val title: String,
    val summary: String,
    @SerialName("saves_tokens") val savesTokens: Boolean = false,
)

@Serializable private class WasmToolDef(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonElement,
)

private val json = Json { ignoreUnknownKeys = true }

/** One loaded `.wasm` plugin instance. rtok dispatches plugins from one thread, so no lock (Rust: Mutex). */
class WasmPlugin private constructor(
    private val manifest: Manifest,
    private val page: DashboardPage,
    private val instance: WasmInstance,
    private val state: GuestState,
    private val fuel: Long,
) : Plugin {
    override fun manifest() = manifest

    override fun dashboardPage() = page

    override fun mcpTools(): List<ToolDef> =
        runCatching { json.decodeFromString<List<WasmToolDef>>(guestBytes("rtok_mcp_tools").decodeToString()) }
            .getOrDefault(emptyList())
            .map { ToolDef(it.name, it.description, it.inputSchema) }

    /**
     * Dispatches one MCP tool export of the guest and flushes its staged measurements to [cx].
     * Like Rust, [name] and [args] are not passed to the guest yet (the demo ABI takes zeros).
     */
    fun invokeMcp(
        @Suppress("unused") name: String,
        @Suppress("unused") args: JsonElement,
        cx: Ctx,
    ): String {
        instance.setFuel(fuel)
        val packed = (instance.call("rtok_on_mcp_tool", List(6) { WasmValue.I32(0) }).single() as WasmValue.I64).v
        val staged = state.staged.toList()
        state.staged.clear()
        staged.forEach(cx::record)
        if (packed < 0) throw WasmException("wasm tool failed")
        return readPacked(instance.memory, packed).decodeToString()
    }

    /** Bytes of a no-argument export returning a packed `(ptr, len)`; empty on any failure (fail open). */
    private fun guestBytes(export: String): ByteArray =
        runCatching {
            instance.setFuel(fuel)
            readPacked(instance.memory, (instance.call(export).single() as WasmValue.I64).v)
        }.getOrDefault(ByteArray(0))

    companion object {
        /** Loads [path] with the default per-call [FUEL]. */
        fun load(
            path: String,
            runtime: WasmRuntime,
            env: WasmHostEnv,
        ): WasmPlugin = loadWithFuel(path, runtime, env, FUEL)

        fun loadWithFuel(
            path: String,
            runtime: WasmRuntime,
            env: WasmHostEnv,
            fuel: Long,
        ): WasmPlugin {
            val bytes = runCatching { rtok.sys.readBytes(path) }.getOrElse { throw WasmException(it.message ?: "read failed", it) }
            val state = GuestState()
            // A `start` function runs guest code too, so instantiation is metered like every call.
            val instance = runtime.instantiate(bytes, hostFunctions(state, env), fuel)
            instance.setFuel(fuel)
            val packed = (instance.call("rtok_manifest").single() as? WasmValue.I64)?.v ?: throw WasmException("rtok_manifest: bad result")
            val parsed =
                runCatching { json.decodeFromString<WasmManifest>(readPacked(instance.memory, packed).decodeToString()) }
                    .getOrElse { throw WasmException(it.message ?: "bad manifest", it) }
            state.pluginId = parsed.id
            if ("hook" in parsed.surfaces) throw WasmException("wasm plugin declares hook surface")
            val surfaces =
                parsed.surfaces.mapNotNull { s ->
                    when (s) {
                        "mcp" -> Surface.Mcp
                        "proxy" -> Surface.Proxy
                        "cli" -> Surface.Cli
                        else -> null
                    }
                }
            if (surfaces.isEmpty()) throw WasmException("wasm plugin has no allowed surfaces")
            return WasmPlugin(Manifest(parsed.id, surfaces, parsed.defaultOn), DashboardPage(parsed.title, parsed.summary, parsed.savesTokens), instance, state, fuel)
        }
    }
}

/** Per-instance host data (Rust `WasmHost`): the plugin id once known and measurements staged by the guest. */
internal class GuestState {
    var pluginId = ""
    val staged = mutableListOf<Measurement>()
}

private fun hostFunctions(
    state: GuestState,
    env: WasmHostEnv,
) = listOf(
    HostFunction("env", "rtok_estimate", listOf(I32, I32, I32), listOf(I32)) { mem, a ->
        val text = memRead(mem, a[0], a[1]).decodeToString()
        val cls =
            when (a[2].i32()) {
                0 -> Class.Code
                1 -> Class.Prose
                2 -> Class.Json
                else -> Class.Cjk
            }
        listOf(WasmValue.I32(env.estimate(text, cls)))
    },
    HostFunction("env", "rtok_record_measurement", listOf(I32, I32), listOf(I32)) { mem, a ->
        val m =
            runCatching { StrictJson.parse(memRead(mem, a[0], a[1]).decodeToString()).jsonObject }
                .getOrElse { throw WasmException(it.message ?: "bad measurement", it) }
        val plugin = m.str("plugin") ?: ""
        if (plugin != state.pluginId) throw WasmException("measurement plugin mismatch")
        state.staged += jsonMeasurement(m, plugin)
        listOf(WasmValue.I32(0))
    },
    HostFunction("env", "rtok_log", listOf(I32, I32, I32, I32), emptyList()) { mem, a ->
        val level = memRead(mem, a[0], a[1]).decodeToString()
        val msg = memRead(mem, a[2], a[3]).decodeToString()
        val name = state.pluginId.ifEmpty { "guest" }
        appendSplit(env.log, errorPath(env.log.path), level, "wasm", name, msg)
        env.stderr("wasm $name $level: $msg\n")
        emptyList()
    },
)

internal fun jsonMeasurement(
    v: JsonObject,
    plugin: String,
) = Measurement(
    plugin = plugin,
    kind = v.str("kind") ?: "",
    beforeBytes = v.long("before_bytes")?.takeIf { it >= 0 }?.toULong() ?: 0u,
    afterBytes = v.long("after_bytes")?.takeIf { it >= 0 }?.toULong() ?: 0u,
    estBefore = v.long("est_before")?.takeIf { it >= 0 }?.toUInt() ?: 0u,
    estAfter = v.long("est_after")?.takeIf { it >= 0 }?.toUInt() ?: 0u,
    refId = v.str("ref_id"),
    callId = v.long("call_id")?.toInt(),
)

private fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.long(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

private fun WasmValue.i32() = (this as WasmValue.I32).v

private fun memRead(
    mem: WasmMemory?,
    ptr: WasmValue,
    len: WasmValue,
): ByteArray = (mem ?: throw WasmException("guest memory export missing")).read(ptr.i32(), len.i32())
