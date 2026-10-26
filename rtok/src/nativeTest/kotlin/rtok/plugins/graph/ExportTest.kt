package rtok.plugins.graph

import kotlinx.serialization.json.*
import rtok.plugin.Runtime
import rtok.store.*
import rtok.support.tmpDir
import rtok.support.withRuntime
import rtok.sys.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.*

@OptIn(ExperimentalEncodingApi::class)
class ExportTest {
    /** `a` calls `shared`, which `b` defines; `a` is linked to `b`. */
    private fun linked(
        tag: String,
        body: (Runtime, String, List<Member>) -> Unit,
    ) = withRuntime(tag) { rt ->
        val dir = canonicalize(tmpDir(tag))!!
        try {
            val ids =
                listOf("a" to "fn a_caller() {\n    shared();\n}\n", "b" to "fn shared() {}\n").map { (n, src) ->
                    createDirAll("$dir/$n")
                    writeString("$dir/$n/lib.rs", src)
                    rt.store.registerProject("$dir/$n", Origin.Manual).id
                }
            rt.store.linkProjects(ids[0], ids[1], LinkKind.Manual, "shared helper")
            body(rt, dir, Scope.resolve(rt.store, null, "$dir/a"))
        } finally {
            removeDirAll(dir)
        }
    }

    private fun q(level: ExportLevel) = ExportQuery(level, redact = true, pretty = true)

    /** Without `exported_at` and `indexed_at`, which a refresh may move. */
    private fun stable(text: String): JsonObject {
        val v = Json.parseToJsonElement(text).jsonObject
        val meta = JsonObject(v["meta"]!!.jsonObject - "exported_at")
        val projects = JsonArray(v["projects"]!!.jsonArray.map { JsonObject(it.jsonObject - "indexed_at") })
        return JsonObject(v + ("meta" to meta) + ("projects" to projects))
    }

    private fun bare(root: String) =
        Export(
            ExportView.SCHEMA,
            listOf(ExportProj(1, "p", root, "manual", "tags", "ok")),
            emptyList(),
            emptyList(),
            emptyList(),
            ExportMeta(listOf("p"), "overview", exportedAt = 0, rtokVersion = "0", redacted = false, partial = false, notes = emptyList()),
        )

    @Test fun symbolsCrossProjectsAndTheLinkCountsTheCalls() =
        linked("t32916-edges") { rt, _, scope ->
            val doc = stable(ExportView.run(rt, scope, q(ExportLevel.Symbols)))
            assertEquals(listOf("a_caller", "shared"), doc["nodes"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }, "$doc")
            assertEquals(1, doc["edges"]!!.jsonArray.size)
            val link = doc["links"]!!.jsonArray[0].jsonObject
            assertEquals("manual", link["kind"]!!.jsonPrimitive.content)
            assertEquals(1, link["references"]!!.jsonPrimitive.int)
            assertEquals("symbols", doc["meta"]!!.jsonObject["level"]!!.jsonPrimitive.content)
            val overview = stable(ExportView.run(rt, scope, q(ExportLevel.Overview)))
            assertTrue(overview["nodes"]!!.jsonArray.isEmpty())
            assertEquals(doc["links"], overview["links"])
        }

    @Test fun aFocusKeepsTheSymbolsWithinDepthAndAnUnknownOneIsAnError() =
        linked("t32916-focus") { rt, _, scope ->
            val doc = stable(ExportView.run(rt, scope, q(ExportLevel.Overview).copy(focus = "shared", depth = 1)))
            assertEquals("focus", doc["meta"]!!.jsonObject["level"]!!.jsonPrimitive.content)
            assertEquals(2, doc["nodes"]!!.jsonArray.size)
            assertTrue(assertFails { ExportView.run(rt, scope, q(ExportLevel.Overview).copy(focus = "nope", depth = 1)) }.message!!.contains("no definition of nope"))
        }

    @Test fun homeTheUserNameAndAbsoluteRootsAreRedacted() {
        var e = bare("/Users/alice/work/alice/p")
        e =
            e.copy(
                projects = e.projects + bare("/Volumes/disk/other").projects,
                nodes = listOf(ExportNode("1:alice/lib.rs:1:f", 1, "function", "f", "alice/lib.rs", 1)),
                edges = listOf(ExportEdge("1:alice/lib.rs:1:f", "1:alice/lib.rs:1:f", "call")),
                meta = e.meta.copy(notes = listOf("see /Users/alice/x", "/Users/alicex/y")),
            )
        val r = ExportView.redact(e, "/Users/alice")
        assertEquals("~/work/<user>/p", r.projects[0].root)
        assertEquals("…/other", r.projects[1].root)
        assertEquals(listOf("see ~/x", "/Users/alicex/y"), r.meta.notes)
        assertEquals("<user>/lib.rs", r.nodes[0].path)
        assertEquals("1:<user>/lib.rs:1:f", r.nodes[0].id)
        assertEquals(r.nodes[0].id, r.edges[0].from)
        assertTrue(r.meta.redacted)
        val text = Json.encodeToString(Export.serializer(), r.copy(meta = r.meta.copy(notes = r.meta.notes.dropLast(1))))
        assertTrue(!text.contains("alice") && !text.contains("/Volumes"), text)
    }

    @Test fun aProjectNamedAfterTheUserIsHiddenInScopeAndDownloadName() {
        val e = bare("/Users/alice/work/alice").let { it.copy(projects = listOf(it.projects[0].copy(name = "alice")), meta = it.meta.copy(scope = listOf("alice", "other"))) }
        val r = ExportView.redact(e, "/Users/alice")
        assertEquals("<user>", r.projects[0].name)
        assertEquals(listOf("<user>", "other"), r.meta.scope)
        assertFalse(Json.encodeToString(Export.serializer(), r).contains("alice"))
        assertFalse(ExportView.downloadName("alice", "overview", "json", "/Users/alice").contains("alice"))
        assertEquals("rtok-graph-other-focus.png", ExportView.downloadName("other", "focus", "png", "/Users/alice"))
    }

    @Test fun theDefaultExportHidesTheRealPathsAndNoRedactKeepsThem() =
        linked("t32916-paths") { rt, _, scope ->
            val doc = stable(ExportView.run(rt, scope, q(ExportLevel.Overview)))
            assertEquals(true, doc["meta"]!!.jsonObject["redacted"]!!.jsonPrimitive.boolean)
            val raw = stable(ExportView.run(rt, scope, q(ExportLevel.Overview).copy(redact = false)))
            val root =
                raw["projects"]!!
                    .jsonArray[0]
                    .jsonObject["root"]!!
                    .jsonPrimitive.content
            assertTrue(root.startsWith("/") && root.endsWith("a"), root)
            assertNotEquals(doc["projects"]!!.jsonArray[0].jsonObject["root"], raw["projects"]!!.jsonArray[0].jsonObject["root"])
        }

    @Test fun theCliAndTheMcpToolGiveTheSameJson() =
        linked("t32916-same") { rt, _, scope ->
            val cli = stable(ExportView.run(rt, scope, q(ExportLevel.Symbols)))
            val mcp = stable(ExportView.call(rt, buildJsonObject { put("level", "symbols") }, scope))
            assertEquals(cli, mcp)
        }

    @Test fun importingASavedExportWritesNothing() =
        linked("t32916-import") { rt, dir, scope ->
            val file = "$dir/saved.json"
            writeString(file, ExportView.run(rt, scope, q(ExportLevel.Symbols)))
            val saved = readToString(file)
            withRuntime("t32916-import-empty") { empty ->
                val shown = stable(ExportView.run(empty, emptyList(), q(ExportLevel.Symbols).copy(from = file)))
                assertEquals(2, shown["nodes"]!!.jsonArray.size)
                assertTrue(empty.store.projects().isEmpty())
                assertTrue(empty.store.projectLinks().isEmpty())
            }
            assertEquals(saved, readToString(file))
            writeString(file, """{"schema":"rtok.graph.v2"}""")
            assertTrue(assertFails { ExportView.read(file) }.message!!.contains("this rtok reads rtok.graph.v1"))
            writeString(file, "not json")
            assertTrue(assertFails { ExportView.read(file) }.message!!.contains("is not a graph export"))
        }

    private fun make(
        project: String,
        level: ExportLevel,
        format: ExportFormat,
    ) = ExportRequest(project, level, format = format)

    private fun decoded(f: ExportFile) = Base64.decode(f.data).decodeToString()

    @Test fun thePageDownloadIsTheFileTheCliWrites() =
        linked("t32940-same") { rt, dir, scope ->
            val cli = ExportView.render(rt, scope, q(ExportLevel.Symbols), ExportImage(ExportFormat.Json)).decodeToString()
            val file = ExportView.pageFile(rt, make("$dir/a", ExportLevel.Symbols, ExportFormat.Json))
            assertEquals("application/json" to "rtok-graph-a-symbols.json", file.mime to file.name)
            val page = decoded(file)
            assertEquals(stable(cli), stable(page))
            assertTrue(page.startsWith("{\n  \"schema\""))
            val f = ExportView.pageFile(rt, make("$dir/a", ExportLevel.Overview, ExportFormat.Json).copy(focus = "shared", depth = 1))
            assertEquals("rtok-graph-a-focus.json", f.name)
            assertEquals("focus", stable(decoded(f))["meta"]!!.jsonObject["level"]!!.jsonPrimitive.content)
            assertTrue(assertFails { ExportView.pageFile(rt, make("", ExportLevel.Symbols, ExportFormat.Json)) }.message!!.contains("needs a project"))
        }

    @Test fun thePageReadsADownloadBackWithoutAStore() =
        linked("t32940-open") { rt, dir, _ ->
            val text = decoded(ExportView.pageFile(rt, make("$dir/a", ExportLevel.Symbols, ExportFormat.Json)))
            assertEquals(2, ExportView.parse(text, "saved.json").nodes.size)
            assertTrue(assertFails { ExportView.parse("{}", "x.json") }.message!!.contains("x.json"))
            assertEquals("a.json", Json.decodeFromString(ImportRequest.serializer(), """{"name":"a.json","text":"{}"}""").name)
        }
}
