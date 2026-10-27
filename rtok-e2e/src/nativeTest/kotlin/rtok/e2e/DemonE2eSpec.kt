@file:Suppress("MagicNumber")
// Port of tests/demon.rs (T20.1): `rtok demon` keeps a service up, and every verb tells the truth about it. The
// supervised service is `rtok mcp` with no stdin: it reaches EOF and exits at once, the crash loop a supervisor
// exists for. Liveness is asked of the kernel, never read out of the state file. Local ports only.

package rtok.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.SIGKILL
import platform.posix.usleep
import rtok.sys.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val FAST = "[demon]\nservices = [\"mcp\"]\nbackoff_ms = 50\nmax_backoff_ms = 100\npoll_ms = 50\n"

private fun Sandbox.demon(extra: String = ""): Sandbox = apply { writeString("$home/config.toml", FAST + extra) }

private fun Sandbox.ok(vararg args: String): String = rtok(*args).also { check(it.code == 0) { "rtok ${args.toList()}: ${it.err}" } }.out

private fun Sandbox.state(): JsonObject? = runCatching { Json.parseToJsonElement(readToString("$home/demon/mcp.json")).jsonObject }.getOrNull()

private fun until(
    what: String,
    f: () -> Boolean,
) {
    val start = TimeSource.Monotonic.markNow()
    while (!f()) {
        check(start.elapsedNow() < 10.seconds) { "$what within 10s" }
        usleep(50_000u)
    }
}

private fun Sandbox.waitRestarts(n: Int): JsonObject {
    var st: JsonObject? = null
    until("$n restarts") {
        state()
            ?.also { st = it }
            ?.get("restarts")
            ?.jsonPrimitive
            ?.int
            ?.let { it >= n } == true
    }
    return st!!
}

/** Runs [body], then kills whatever it left up so no supervisor outlives the test. */
private fun withDemon(
    tag: String,
    extra: String = "",
    body: (Sandbox) -> Unit,
) = Sandbox(tag).use { s ->
    s.demon(extra)
    try {
        body(s)
    } finally {
        s.rtok("demon", "kill")
    }
}

private fun JsonArray.row(name: String) = first { it.jsonObject["service"]?.jsonPrimitive?.content == name }.jsonObject

class DemonE2eSpec :
    FunSpec({
        test("a service that exits comes back and stop takes the whole tree down") {
            withDemon("demon-cycle") { s ->
                s.ok("demon", "start")
                val sup = s.waitRestarts(2)["supervisor"]!!.jsonPrimitive.int
                processAlive(sup) shouldBe true
                s.ok("demon", "status") shouldContain "running"
                s.ok("demon", "stop")
                s.state() shouldBe null
                processAlive(sup) shouldBe false
                exists("${s.home}/demon/mcp.stop") shouldBe true
                usleep(300_000u)
                s.state() shouldBe null
            }
        }
        test("status asks the kernel rather than believing the state file") {
            withDemon("demon-truth") { s ->
                s.ok("demon", "start", "mcp")
                val sup = s.waitRestarts(1)["supervisor"]!!.jsonPrimitive.int
                platform.posix.kill(sup, SIGKILL)
                until("the supervisor dies") { !processAlive(sup) }
                s.state() shouldNotBe null
                until("status reads the killed supervisor as stopped") { "stopped" in s.ok("demon", "status", "mcp") }
                s.ok("demon", "status", "mcp") shouldNotContain "running"
                s.ok("demon", "kill", "mcp")
                s.state() shouldBe null
            }
        }
        test("a second start is refused and status names every service") {
            withDemon("demon-once") { s ->
                s.ok("demon", "start", "mcp")
                val first = s.waitRestarts(1)["supervisor"]!!.jsonPrimitive.int
                s.ok("demon", "start", "mcp") shouldContain "already running"
                s.state()!!["supervisor"]!!.jsonPrimitive.int shouldBe first
                val status = s.ok("demon", "status")
                for (svc in listOf("proxy", "mcp", "web", "hook")) status shouldContain svc
                Regex("stopped").findAll(status).count() shouldBe 3
                s.rtok("demon", "start", "rm -rf /").also {
                    it.code shouldNotBe 0
                    it.err shouldContain "invalid value"
                    it.err shouldContain "proxy"
                    it.err shouldContain "web"
                }
                s.ok("demon", "stop", "mcp")
            }
        }
        test("status names the proxy endpoint running or stopped") {
            val port = 18_000 + pid() % 1000
            withDemon("demon-endpoint", "[proxy]\nport = $port\n") { s ->
                s.ok("demon", "status") shouldContain "127.0.0.1:$port"
                val rows = Json.parseToJsonElement(s.ok("demon", "status", "--json")).jsonArray
                rows.row("proxy")["endpoint"]!!.jsonPrimitive.content shouldBe "127.0.0.1:$port"
                rows.row("mcp")["endpoint"] shouldBe JsonNull
                s.ok("demon", "start", "proxy")
                until("the proxy listens") { "running" in s.ok("demon", "status", "proxy") }
                s.ok("demon", "status", "proxy") shouldContain "127.0.0.1:$port"
                s.ok("demon", "stop", "proxy")
            }
        }
        test("upgrade stops before the replace and starts after") {
            withDemon("demon-upgrade") { s ->
                s.ok("demon", "start")
                s.waitRestarts(1)
                val script = "${s.root}/replace.sh"
                writeString(script, "#!/bin/sh\n[ ! -f '${s.home}/demon/mcp.json' ] || exit 2\ntouch '${s.root}/replaced'\n")
                platform.posix.chmod(script, 0x1edu)
                s.rtok("demon", "upgrade", env = mapOf("RTOK_UPDATE_CMD" to script)).also { it.code shouldBe 0 }
                exists("${s.root}/replaced") shouldBe true
                s.waitRestarts(1)
            }
        }
        test("upgrade starts again when the replace fails") {
            withDemon("demon-upgrade-fail") { s ->
                s.ok("demon", "start")
                s.waitRestarts(1)
                val script = "${s.root}/replace.sh"
                writeString(script, "#!/bin/sh\nexit 1\n")
                platform.posix.chmod(script, 0x1edu)
                s.rtok("demon", "upgrade", env = mapOf("RTOK_UPDATE_CMD" to script)).code shouldNotBe 0
                s.waitRestarts(1)
            }
        }
        test("the hook service runs the resident and stop takes it down") {
            // Short: a Unix socket path must fit 104 bytes, and the sandbox's temp path does not.
            val short = "/tmp/rtd-${pid()}"
            removeDirAll(short)
            createDirAll(short)
            Sandbox("demon-hook").use { s ->
                val env = mapOf("RTOK_HOME" to short)
                val sock = rtok.hook.endpoint(short) ?: error("endpoint too long: $short")

                fun listening() = rtok.hook.connect(sock)?.also { platform.posix.close(it) } != null
                try {
                    s.rtok("demon", "start", "hook", env = env).code shouldBe 0
                    until("the resident listens") { listening() }
                    s.rtok("demon", "stop", "hook", env = env).code shouldBe 0
                    until("the resident is gone") { !listening() }
                } finally {
                    s.rtok("demon", "kill", env = env)
                    removeDirAll(short)
                }
            }
        }
    })
