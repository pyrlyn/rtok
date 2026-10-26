package rtok.support

import kotlinx.serialization.json.*
import rtok.config.Config
import rtok.plugin.Runtime
import rtok.plugin.sdk.*

/** A runtime whose archive dir is a fresh temp dir, configured by [tune]; closed after [body]. */
fun withRuntime(
    tag: String,
    tune: (Config) -> Config = { it },
    body: (Runtime) -> Unit,
) {
    installDb()
    val rt = Runtime.inMemory("s")
    rt.config = tune(rt.config.copy(core = rt.config.core.copy(archiveDir = tmpDir("archive-$tag"))))
    rt.use(body)
}

/** `proxy.mode = "compress"` plus [f] on `[plugins.archive]`. */
fun compress(f: (rtok.config.Archive) -> rtok.config.Archive = { it }): (Config) -> Config =
    { c ->
        c.copy(proxy = c.proxy.copy(mode = "compress"), plugins = c.plugins.copy(archive = f(c.plugins.archive)))
    }

/**
 * Test wire over a JSON array of payloads: element i is `tu-(i+1)`, turn = distance from the end.
 * The refs write back into [req], read the result with `values()`.
 */
class ListWire(
    private val name: String = "pixel",
) : ToolResults {
    private fun turns(req: WireRequest) = (req.body as JsonArray).indices.map { it to req.body.jsonArray.size - it - 1 }

    override fun toolResults(req: WireRequest) = turns(req).map { (i, t) -> ToolResultRef(req, listOf(i), t, "tu-${i + 1}") }

    override fun liveBlobs(req: WireRequest) = turns(req).map { (i, t) -> BlobRef(req, listOf(i), t) }

    override fun skills(req: WireRequest) = turns(req).map { (i, t) -> SkillRef(req, listOf(i), t, "tu-${i + 1}", name) }
}

fun request(values: List<String>) = WireRequest(ListWire(), JsonArray(values.map(::JsonPrimitive)))

fun WireRequest.values(): List<String> = body.jsonArray.map { it.jsonPrimitive.content }
