// Port of src/bench.rs's overlay tests (T385.3, T385.9.3): the shipped live-arm overlays load through the config
// layers and schedule their arms. The overlays and their settings files are copied to fixtures/bench. Dry runs only.
@file:Suppress("MagicNumber")

package rtok.bench

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rtok.config.Layers
import rtok.sys.createDirAll
import rtok.sys.pid
import rtok.sys.readToString
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val FIXTURES = rtok.config.envVar("RTOK_FIXTURES").orEmpty()

class BenchOverlayTest {
    private fun home(name: String) =
        "${tempDir()}/rtok-bench-overlay-${pid()}-$name".also {
            removeDirAll(it)
            createDirAll(it)
        }

    private fun settings(path: String) = Json.parseToJsonElement(readToString(path)).jsonObject

    private fun url(v: JsonObject) =
        v
            .getValue("env")
            .jsonObject
            .getValue("ANTHROPIC_BASE_URL")
            .jsonPrimitive.content

    /** T385.3: the defaults overlay runs exactly its three arms, each on a proxy port of its own. */
    @Test fun theDefaultsOverlayRunsThreeArmsOnTheirOwnProxies() {
        val home = home("defaults")
        val c = Layers.load(home, "$FIXTURES/bench/configs/defaults/bench.toml", null)
        assertEquals(
            listOf("a", "b", "context"),
            c.bench.configs.keys
                .toList(),
        )
        // The overlay's paths are relative to the checkout root, which the fixtures directory mirrors.
        val urls =
            c.bench.configs.values
                .map { url(settings("$FIXTURES/$it")) }
        urls.forEach { assertTrue(it.startsWith("http://127.0.0.1:88"), it) }
        assertEquals(3, urls.toSet().size, urls.toString())
        val dry = c.copy(bench = c.bench.copy(tasks = "$FIXTURES/${c.bench.tasks}", dryRun = true, runs = 1u))
        assertEquals(6 * 3, Bench.run(dry).lines().count { it.isNotEmpty() })
        removeDirAll(home)
    }

    /** T385.9.3: the Gate P28 overlay runs two arms whose settings differ only in the proxy URL. */
    @Test fun theP28OverlayRunsTwoArmsThatDifferOnlyInTheirProxy() {
        val home = home("p28")
        val dir = "$FIXTURES/bench/p28"
        val c = Layers.load(home, "$dir/bench.toml", null)
        assertEquals(
            listOf("a", "b"),
            c.bench.configs.keys
                .toList(),
        )
        val all =
            c.bench.configs.values
                .map { settings("$dir/$it") }
        assertEquals(listOf("http://127.0.0.1:8811", "http://127.0.0.1:8812"), all.map(::url))
        val rest =
            all.map { v ->
                JsonObject(v + ("env" to JsonObject(v.getValue("env").jsonObject - "ANTHROPIC_BASE_URL")))
            }
        assertEquals(rest[0], rest[1], "only the proxy URL may differ")
        val dry = c.copy(bench = c.bench.copy(tasks = "$dir/${c.bench.tasks}", dryRun = true, runs = 1u))
        assertEquals(6 * 2, Bench.run(dry).lines().count { it.isNotEmpty() })
        removeDirAll(home)
    }
}
