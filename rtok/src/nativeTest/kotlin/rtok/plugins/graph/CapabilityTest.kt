package rtok.plugins.graph

import platform.posix.usleep
import rtok.config.Config
import rtok.plugin.sdk.Ctx
import rtok.support.tmpDir
import rtok.support.withRuntime
import rtok.sys.*
import kotlin.concurrent.AtomicInt
import kotlin.test.*

class CapabilityTest {
    private fun backend(name: String): (Config) -> Config = { c -> c.copy(plugins = c.plugins.copy(graph = c.plugins.graph.copy(backend = name))) }

    private fun project(
        tag: String,
        body: (Ctx, String) -> Unit,
    ) = withRuntime(tag, backend("auto")) { rt ->
        val root = tmpDir(tag) + "/proj"
        createDirAll(root)
        writeString("$root/Cargo.toml", "[package]\nname = \"x\"\n")
        try {
            body(Ctx(rt), root)
        } finally {
            Capability.forget(root)
            removeDirAll(root)
        }
    }

    private class Counts {
        val probes = AtomicInt(0)
        val server = AtomicInt(0)
    }

    private val missing = Absent(true, "lsp: rust-analyzer not on PATH")

    /** The door's rendering of one request: the server's answer, else the tags fallback with the reason. */
    private fun request(
        cx: Ctx,
        root: String,
        n: Counts,
        installed: () -> Boolean,
        server: () -> String,
    ): String {
        val asked =
            Capability.ask(cx, root, GraphCore.backendName(cx, root), {
                n.probes.incrementAndGet()
                if (installed()) null else missing
            }) {
                n.server.incrementAndGet()
                server()
            }
        return when (asked) {
            is Asked.Tags -> "(tags; lsp: ${asked.rec.reason?.removePrefix("lsp: ")})\ntags"
            is Asked.Lsp -> asked.answer.fold({ "(lsp)\n$it" }, { "(tags; lsp: ${Capability.reasonOf(it).removePrefix("lsp: ")})\ntags" })
        }
    }

    @Test fun aHundredRequestsAfterTheFirstRunNoCheck() =
        project("t32911-once") { cx, root ->
            val n = Counts()
            var installed = false
            repeat(100) { request(cx, root, n, { installed }) { "a.rs:1 function" } }
            installed = true
            repeat(100) {
                val out = request(cx, root, n, { installed }) { "a.rs:1 function" }
                assertTrue(out.startsWith("(tags; lsp: rust-analyzer not on PATH)"), out)
            }
            assertEquals(1, n.probes.value)
            val rec =
                Capability.reprobe(cx, root, GraphCore.backendName(cx, root)) {
                    n.probes.incrementAndGet()
                    null
                }
            assertEquals(Chosen.Lsp to null, rec.backend to rec.reason)
            repeat(100) { assertEquals("(lsp)\na.rs:1 function", request(cx, root, n, { installed }) { "a.rs:1 function" }) }
            assertEquals(2 to 100, n.probes.value to n.server.value)
        }

    @Test fun aServerThatBreaksIsDowngradedOnceButAnErrorAnswerIsNot() =
        project("t32911-break") { cx, root ->
            val n = Counts()
            var calls = 0
            val flaky = {
                when (calls++) {
                    0 -> "a.rs:1 function"
                    1 -> throw Refused("lsp textDocument/definition: bad params")
                    else -> throw IllegalStateException("lsp: eof; exited signal: 9")
                }
            }
            val outs = (0 until 4).map { request(cx, root, n, { true }, flaky) }
            assertEquals("(lsp)\na.rs:1 function", outs[0])
            assertTrue(outs[1].startsWith("(tags; lsp: lsp textDocument/definition"), outs.toString())
            assertTrue(outs[2].startsWith("(tags; lsp: eof; exited signal: 9)"), outs.toString())
            assertTrue(outs[3].startsWith("(tags; lsp: eof"), outs.toString())
            assertEquals(3 to 1, n.server.value to n.probes.value)
            val rec = Capability.mirrored(cx, root)!!
            assertEquals(Chosen.Tags, rec.backend)
            assertTrue(rec.reason!!.contains("eof"))
            assertTrue(rec.down)
            assertNotNull(rec.nextProbeAt)
        }

    @Test fun aBackendChangeRechecksOnlyTheProjectItReaches() =
        project("t32911-config") { cx, root ->
            val n = Counts()
            request(cx, root, n, { true }) { "x" }
            request(cx, root, n, { true }) { "x" }
            assertEquals(1, n.probes.value)
            Capability.serverReady(cx, root, "lsp") {
                n.probes.incrementAndGet()
                null
            }
            assertEquals(2, n.probes.value, "another backend value is a replaced record")
            assertEquals("lsp", Capability.mirrored(cx, root)!!.config)
        }

    @Test fun anotherProcessAdoptsAFreshRecordAndChecksADueOneItself() =
        project("t32911-mirror") { cx, root ->
            val n = Counts()
            var installed = false
            request(cx, root, n, { installed }) { "a.rs:1 function" }
            val rec = Capability.mirrored(cx, root)!!
            assertEquals(Chosen.Tags, rec.backend)
            assertEquals("rust", rec.language)
            assertEquals(rec.checkedAt + Capability.RECHECK_S, rec.nextProbeAt)
            assertFalse(Capability.encode(rec).contains("\"down\""), "serde skips a false down")

            Capability.forget(root)
            val second = Counts()
            assertTrue(request(cx, root, second, { installed }) { "a.rs:1 function" }.startsWith("(tags; lsp: rust-analyzer not on PATH)"))
            assertEquals(0, second.probes.value)

            Capability.writeMirror(cx, Index.canon(root), rec.copy(nextProbeAt = Capability.now() - 1))
            Capability.forget(root)
            installed = true
            assertEquals("(lsp)\na.rs:1 function", request(cx, root, second, { installed }) { "a.rs:1 function" })
            assertEquals(1, second.probes.value)
        }

    @Test fun clipKeepsTheFirstLineAtMost120Chars() {
        assertEquals("a", Capability.clip("a\nb"))
        assertEquals(120, Capability.clip("x".repeat(300)).length)
    }

    /** Eight first requests at once: one check, and a server that cannot start is tried by the first alone. */
    @Test fun concurrentFirstRequestsShareOneCheckAndOneServerAttempt() {
        for (working in listOf(true, false)) {
            project("t32911-flight-$working") { cx, root ->
                val n = Counts()
                val errs =
                    rtok.support.parallel(8) {
                        request(cx, root, n, { true }) {
                            usleep(30_000u)
                            if (working) "a.rs:1 function" else throw IllegalStateException("lsp: eof")
                        }
                    }
                assertEquals(emptyList(), errs)
                assertEquals(1, n.probes.value)
                assertEquals(if (working) 8 else 1, n.server.value, "working: $working")
            }
        }
    }
}
