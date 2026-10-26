// Port of src/plugins/docs/mod.rs tests.
package rtok.plugins.docs

import kotlin.test.*
import kotlinx.serialization.json.*
import rtok.config.Config
import rtok.plugin.Runtime
import rtok.plugin.sdk.Class
import rtok.store.*
import rtok.support.*
import rtok.sys.*

private const val LOCK = """
version = 3

[[package]]
name = "serde"
version = "1.0.210"
source = "registry+https://github.com/rust-lang/crates.io-index"
checksum = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
"""

private fun on(f: (rtok.config.Docs) -> rtok.config.Docs = { it }): (Config) -> Config = { c -> c.copy(plugins = c.plugins.copy(docs = f(c.plugins.docs.copy(enabled = true)))) }
private fun workspace(vararg files: Pair<String, String>) = tmpDir("docs-ws").also { d -> files.forEach { (n, b) -> writeString("$d/$n", b) } }
private fun <T> inDir(dir: String, f: () -> T): T { val prev = runCatching { cwd() }.getOrElse { tempDir() }; setCwd(dir); try { return f() } finally { setCwd(prev) } }
private fun <T> withLockCwd(lock: String, f: () -> T) = inDir(workspace("Cargo.lock" to lock), f)
private fun idsOf(out: String) = Json.parseToJsonElement(out).jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.long }
private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

class DocsTest {
    @Test fun resolveLockPrintsSerdeVersion() { assertEquals("serde" to "1.0.210", resolveLock(LOCK, "serde")); assertNull(resolveLock(LOCK, "nope")) }

    @Test fun resolveLockRefusesPathLikeNamesAndVersions() {
        for (v in listOf("../../../tmp/x", "/tmp/x", "1.0/../x")) assertNull(resolveLock("version = 4\n[[package]]\nname = \"evil\"\nversion = \"$v\"\n", "evil"), v)
        assertNotNull(resolveLock("version = 4\n[[package]]\nname = \"a_b-c\"\nversion = \"1.0.0-rc.1+g1\"\n", "a_b-c"))
    }

    @Test fun docsResolvePrintsSerdeVersionFromFixtureLock() = withRuntime("docs-lock", on()) { rt ->
        val v = obj(withLockCwd(LOCK) { docsResolve(rt, "serde") })
        assertEquals("serde", v["name"]!!.jsonPrimitive.content); assertEquals("1.0.210", v["version"]!!.jsonPrimitive.content); assertEquals(false, v["cached"]!!.jsonPrimitive.boolean)
    }

    @Test fun docsResolveUnknownNameIsAMissLine() = withRuntime("docs-miss", on()) { rt ->
        val out = withLockCwd(LOCK) { docsResolve(rt, "definitely-missing-crate") }; assertTrue(out.startsWith("not in Cargo.lock:"), out)
    }

    @Test fun queryDeserializeRanksMatchingPathFirstUnderBudget() = withRuntime("docs-q", on { it.copy(maxTokens = 800u) }) { rt ->
        rt.store.replaceDocCrate("serde", "1.0.210", "sha", 1, 0, listOf(
            Triple("serde::Serialize", "trait", "Serialize a data structure from Rust to a data format."),
            Triple("serde::Deserialize", "trait", "Deserialize a data structure from a data format into Rust.")))
        val out = docsQuery(rt, "serde", "1.0.210", "Deserialize")
        val arr = Json.parseToJsonElement(out).jsonArray; assertTrue(arr.isNotEmpty(), out)
        assertTrue("Deserialize" in arr[0].jsonObject["path"]!!.jsonPrimitive.content, "first hit should be Deserialize: $out")
        assertTrue(rt.estimate(out, Class.Prose) <= 800u, out)
        assertTrue(rt.store.listMeasurements("docs").any { it.kind == "query" }, "query records a Measurement")
    }

    @Test fun localLlmsDocsAreScopedToTheirWorkspace() = withRuntime("docs-ws", on()) { rt ->
        val a = workspace("llms.txt" to "# Alpha\nMARKER_ALPHA body\n"); val b = workspace()
        rt.store.replaceDocCrate("llms", "local", "old", 0, 0, listOf(Triple("Legacy", "llms", "MARKER_LEGACY")))
        fun query(d: String, q: String) = inDir(d) { docsQuery(rt, "llms", null, q) }
        fun cached(d: String) = obj(inDir(d) { docsResolve(rt, "llms") })["cached"]!!.jsonPrimitive.boolean
        assertEquals(1, idsOf(query(a, "MARKER_ALPHA")).size); assertTrue(cached(a)); assertFalse(cached(b), "B has no llms file")
        assertEquals("not cached: rtok docs fetch llms", query(b, "MARKER_ALPHA")); assertEquals("[]", query(a, "MARKER_LEGACY"))
        writeString("$b/llms.txt", "# Beta\nMARKER_BETA body\n")
        assertEquals("[]", query(b, "MARKER_ALPHA")); assertEquals(1, idsOf(query(b, "MARKER_BETA")).size)
        assertEquals(1, idsOf(query(a, "MARKER_ALPHA")).size, "B did not overwrite A"); assertEquals("[]", query(a, "MARKER_BETA"))
        removeFile("$a/llms.txt"); assertFalse(cached(a), "a deleted source is no longer served")
        writeString("$a/llms.txt", "# Alpha\nMARKER_AGAIN body\n"); assertEquals(1, idsOf(query(a, "MARKER_AGAIN")).size)
    }

    @Test fun unchangedLocalDocsKeepTheirIdsAndEditsRefresh() = withRuntime("docs-ids", on()) { rt ->
        val ws = workspace("llms.txt" to "# Guide\nMARKER_ONE body\n")
        fun q(m: String) = inDir(ws) { docsQuery(rt, "llms", null, m) }
        val first = idsOf(q("MARKER_ONE")); assertEquals(1, first.size)
        rt.store.replaceDocCrate("serde", "1.0.210", "sha", 1, 0, listOf(Triple("serde::X", "struct", "other")))
        inDir(ws) { docsResolve(rt, "llms") }
        assertEquals(first, idsOf(q("MARKER_ONE")), "ids survive an unrelated read")
        assertTrue("MARKER_ONE" in obj(docsGet(rt, first[0].toInt()))["docs"]!!.jsonPrimitive.content)
        writeString("$ws/llms.txt", "# Guide\nMARKER_TWO body\n")
        assertEquals("[]", q("MARKER_ONE")); assertEquals(1, idsOf(q("MARKER_TWO")).size, "an edit is searchable")
    }

    @Test fun queryMeasurementUsesOneFullBodyBaseline() {
        val bodies = (0 until 3).map { "Deserialize variant $it. " + "long explanation text ".repeat(120) }
        fun seed(rt: Runtime) = rt.store.replaceDocCrate("serde", "1.0.210", "sha", 1, 0, bodies.mapIndexed { i, d -> Triple("serde::D$i", "trait", d) })
        val all = bodies.joinToString("\n")
        for (max in listOf(800u, 60u)) withRuntime("docs-meas", on { it.copy(snippetChars = 30u, maxTokens = max) }) { rt ->
            seed(rt)
            val out = docsQuery(rt, "serde", "1.0.210", "Deserialize"); assertTrue(idsOf(out).isNotEmpty(), out)
            val r = rt.store.listMeasurements("docs").single()
            assertEquals(all.length.toLong() - 2, r.beforeBytes.toLong()); assertEquals(rt.estimate(all, Class.Prose).toLong(), r.estBefore.toLong())
            assertEquals(out.length.toLong(), r.afterBytes.toLong()); assertEquals(rt.estimate(out, Class.Prose).toLong(), r.estAfter.toLong())
            assertTrue(r.estBefore.toLong() > r.estAfter.toLong())
        }
        withRuntime("docs-meas-none", on()) { rt ->
            seed(rt); assertEquals("[]", docsQuery(rt, "serde", "1.0.210", "absentword")); assertTrue(rt.store.listMeasurements("docs").isEmpty())
        }
    }

    @Test fun queryUncachedPrintsNotCachedWithoutHttp() = withRuntime("docs-empty", on()) { rt ->
        assertEquals("not cached: rtok docs fetch serde", docsQuery(rt, "serde", "1.0.210", "Deserialize"))
    }

    @Test fun mcpSurfaceStaysWithinSixtyDescriptionTokens() = withRuntime("docs-surface") { rt ->
        val total = DocsPlugin.mcpTools().sumOf { rt.estimate(it.description, Class.Prose).toLong() }; assertTrue(total <= 60, "docs tool surface is $total tokens")
    }

    @Test fun fetchNon200LeavesCacheUntouched() = withRuntime("docs-404", on()) { rt ->
        rt.store.replaceDocCrate("serde", "1.0.210", "old", 1, 0, listOf(Triple("serde::Keep", "struct", "stay")))
        val out = withLockCwd(LOCK) { fetchCrate(rt, "serde", { 404 to "nope".encodeToByteArray() }) { error("no decode on a 404") } }
        assertTrue("404" in out, out); assertEquals(1, rt.store.searchDocItems("serde", "1.0.210", "stay", 5u, 40u).size, "old cache kept")
    }

    @Test fun llmsTxtSplitsOnHashHeadings() {
        val s = splitLlms("# One\nbody a\n# Two\nbody b\n")
        assertEquals(2, s.size); assertEquals("One", s[0].first); assertEquals("body a", s[0].third); assertEquals("Two", s[1].first)
    }

    @Test fun fetchCachesTheDecodedIndex() = withRuntime("docs-fetch", on()) { rt ->
        rt.config.home = tmpDir("docs-home")
        val json = """{"format_version":39,"paths":{"0:1":{"path":["serde","Deserialize"],"kind":"trait"},"0:2":{"path":["serde","Nodoc"],"kind":{"struct":{}}}},"index":{"0:1":{"docs":"Deserialize docs"},"0:2":{"docs":""}}}"""
        var url = ""
        val out = withLockCwd(LOCK) { fetchCrate(rt, "serde", { url = it; 200 to "ZST".encodeToByteArray() }) { json.encodeToByteArray() } }
        assertEquals("https://docs.rs/crate/serde/1.0.210/json.zst", url); assertEquals("cached serde 1.0.210 (1 items)", out)
        assertTrue(exists("${rt.config.home}/docs/serde/1.0.210/rustdoc.json.zst.sha256"))
        assertEquals(39 to listOf(Triple("serde::Deserialize", "trait", "Deserialize docs")), indexRustdoc(Json.parseToJsonElement(json)))
    }
}
