package rtok.plugins.graph

import kotlinx.serialization.json.*
import rtok.plugin.Runtime
import rtok.store.*
import rtok.support.tmpDir
import rtok.support.withRuntime
import rtok.sys.*
import kotlin.test.*

class DiffTest {
    private fun git(
        dir: String,
        vararg args: String,
    ) {
        val (c, _) = ProgressRunner("", "git", listOf("-C", dir, "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + args).quiet().runCapped(1L shl 20)
        check(c.exitCode == 0) { "git ${args.toList()}: ${c.stderr.decodeToString()}" }
    }

    private fun commit(dir: String) {
        git(dir, "add", "-A")
        git(dir, "commit", "-q", "-m", "c")
    }

    private fun project(
        dir: String,
        name: String,
        files: List<Pair<String, String>>,
    ): String {
        val root = "$dir/$name"
        createDirAll(root)
        for ((p, src) in files) {
            parent("$root/$p")?.let(::createDirAll)
            writeString("$root/$p", src)
        }
        git(root, "init", "-q", "-b", "main")
        commit(root)
        return root
    }

    private fun solo(root: String) = listOf(Member("", root))

    private fun ask(
        rt: Runtime,
        scope: List<Member>,
        from: String,
        to: String? = null,
    ) = GraphDiffs.run(rt, scope, DiffQuery(listOf(from), to))

    private fun cx(
        tag: String,
        tune: (rtok.config.Config) -> rtok.config.Config = { it },
        body: (Runtime, String) -> Unit,
    ) = withRuntime(tag, tune) { rt ->
        val dir = canonicalize(tmpDir(tag))!!
        try {
            body(rt, dir)
        } finally {
            removeDirAll(dir)
        }
    }

    /** `a` calls `shared`, which `b` defines; `a` is linked to `b`. */
    private fun linked(
        tag: String,
        body: (Runtime, String, List<Member>) -> Unit,
    ) = cx(tag) { rt, dir ->
        val a = project(dir, "a", listOf("lib.rs" to "fn a_caller() {\n    shared();\n}\n"))
        val b = project(dir, "b", listOf("lib.rs" to "fn shared() {}\n"))
        val ids = listOf(a, b).map { rt.store.registerProject(it, Origin.Manual).id }
        rt.store.linkProjects(ids[0], ids[1], LinkKind.Manual, null)
        body(rt, dir, Scope.resolve(rt.store, null, a))
    }

    @Test fun changedDefinitionsWithoutATestCallerAreListedAsUntested() =
        cx("t664-untested") { rt, dir ->
            val root = project(dir, "p", listOf("lib.rs" to "pub fn covered() {}\npub fn bare() {}\n", "tests/t.rs" to "fn t() {\n    covered();\n}\n"))
            writeString("$root/lib.rs", "pub fn covered() { let _ = 1; }\npub fn bare() { let _ = 2; }\n")
            var out = ask(rt, solo(root), "HEAD")
            assertTrue(out.contains("untested: bare\n"), out)
            assertFalse(out.contains("covered\n"), out)
            writeString("$root/lib.rs", "pub fn covered() { let _ = 1; }\npub fn bare() {}\n")
            out = ask(rt, solo(root), "HEAD")
            assertFalse(out.contains("untested:"), out)
        }

    @Test fun aSignatureChangeInBListsAsCallSites() =
        linked("t32918-sig") { rt, dir, scope ->
            writeString("$dir/b/lib.rs", "fn shared(x: i32) {}\n")
            val out = ask(rt, scope, "HEAD")
            assertTrue(out.startsWith("graph diff HEAD -> working (tags)\n"), out)
            assertTrue(out.contains("[b] 1 changed"), out)
            assertTrue(out.contains("function shared  lib.rs:1  [signature] 1 callers"), out)
            assertTrue(out.contains("    [a] lib.rs a_caller d1"), out)
        }

    @Test fun aBodyChangeIsAChange() =
        cx("t32918-body") { rt, dir ->
            val root = project(dir, "p", listOf("lib.rs" to "fn f() {\n    one();\n}\n", "same.rs" to "fn s() {}\n"))
            writeString("$root/lib.rs", "fn f() {\n    two();\n}\n")
            val out = ask(rt, solo(root), "HEAD")
            assertTrue(out.contains("1 changed · 0 added · 0 removed"), out)
            assertTrue(out.contains("[body]"), out)
            assertTrue(out.contains("edges +1 -1"), out)
        }

    @Test fun aRenameIsARename() =
        cx("t32918-rename") { rt, dir ->
            val root = project(dir, "p", listOf("lib.rs" to "fn old_name() {\n    work();\n}\nfn work() {}\n"))
            writeString("$root/lib.rs", "fn new_name() {\n    work();\n}\nfn work() {}\n")
            val out = ask(rt, solo(root), "HEAD")
            assertTrue(out.contains("1 renamed"), out)
            assertTrue(out.contains("old_name -> new_name"), out)
            assertFalse(out.contains("\nadded ("), out)
            assertFalse(out.contains("\nremoved ("), out)
            assertTrue(out.contains("edges +0 -0"), out)
        }

    @Test fun twoCandidatesForOneRenameStayARemovePlusAnAdd() =
        cx("t32918-ambiguous") { rt, dir ->
            val root = project(dir, "p", listOf("lib.rs" to "fn one() {\n    w();\n}\nfn two() {\n    w();\n}\n"))
            writeString("$root/lib.rs", "fn uno() {\n    w();\n}\nfn dos() {\n    w();\n}\n")
            val out = ask(rt, solo(root), "HEAD")
            assertTrue(out.contains("0 renamed"), out)
            assertTrue(out.contains("2 added") && out.contains("2 removed"), out)
        }

    @Test fun aDefinitionInAnotherFileIsMoved() =
        cx("t32918-moved") { rt, dir ->
            val root = project(dir, "p", listOf("a.rs" to "fn helper() {\n    work();\n}\nfn keep() {}\n"))
            writeString("$root/a.rs", "fn keep() {}\n")
            writeString("$root/b.rs", "fn helper() {\n    work();\n}\n")
            val out = ask(rt, solo(root), "HEAD")
            assertTrue(out.contains("1 moved"), out)
            assertTrue(out.contains("function helper  a.rs -> b.rs"), out)
            assertTrue(out.contains("0 added · 0 removed"), out)
        }

    @Test fun theDiffCountsTheFilesOfTheRowsItLists() =
        linked("t32943-diff") { rt, dir, scope ->
            fun counted(): Pair<String, TallyCounts> {
                Tally.arm()
                val answer = GraphDiffs.call(rt, JsonObject(emptyMap()), scope)
                return answer to Tally.take()
            }
            var (answer, got) = counted()
            assertTrue(answer.contains("no graph changes"), answer)
            assertEquals(TallyCounts(0, 0, 0), got)
            writeString("$dir/b/lib.rs", "fn shared(x: i32) {}\n")
            writeString("$dir/a/new.rs", "fn fresh() {}\n")
            counted().let {
                answer = it.first
                got = it.second
            }
            assertTrue(answer.contains("[signature] 1 callers"), answer)
            assertTrue(answer.contains("function fresh  new.rs:1"), answer)
            assertTrue(answer.contains("[a] lib.rs a_caller d1"), answer)
            assertEquals(TallyCounts(0, 2, 2), got)
        }

    @Test fun addedAndRemovedDefinitionsAreListed() =
        cx("t32918-addrm") { rt, dir ->
            val root = project(dir, "p", listOf("lib.rs" to "fn gone() {}\nfn stays() {}\n"))
            writeString("$root/lib.rs", "fn stays() {}\nfn fresh() {\n    other();\n}\n")
            val out = ask(rt, solo(root), "HEAD")
            assertTrue(out.contains("added (1)\n  function fresh"), out)
            assertTrue(out.contains("removed (1)\n  function gone"), out)
        }

    @Test fun twoRevisionsCompareWithoutTheWorkingTree() =
        cx("t32918-revs") { rt, dir ->
            val root = project(dir, "p", listOf("lib.rs" to "fn f() {}\n"))
            writeString("$root/lib.rs", "fn f(x: u8) {}\n")
            commit(root)
            writeString("$root/lib.rs", "fn f(x: u8, y: u8) {}\nfn dirty() {}\n")
            val out = ask(rt, solo(root), "HEAD~1", "HEAD")
            assertTrue(out.startsWith("graph diff HEAD~1 -> HEAD (tags)\n"), out)
            assertTrue(out.contains("1 changed · 0 added"), out)
            assertFalse(out.contains("dirty"), out)
            assertTrue(ask(rt, solo(root), "HEAD", "HEAD").contains("no graph changes"))
        }

    @Test fun anUnknownRefAndADirectoryWithoutGitAreErrorsThatNameThem() =
        cx("t32918-errors") { rt, dir ->
            val root = project(dir, "p", listOf("lib.rs" to "fn f() {}\n"))
            for (bad in listOf("nope", "--output=x", "")) {
                val err = assertFails { ask(rt, solo(root), bad) }.message!!
                assertTrue(err.contains("unknown ref `$bad`"), err)
            }
            createDirAll("$dir/plain")
            val err = assertFails { ask(rt, solo("$dir/plain"), "HEAD") }.message!!
            assertTrue(err.contains("is not a git repository"), err)
            assertFalse(exists("$root/x"))
        }

    @Test fun aLongAnswerIsCutWithAnIdThatPagesTheRest() =
        cx("t32918-cap") { rt, dir ->
            val root = project(dir, "p", listOf("lib.rs" to "fn base() {}\n"))
            writeString("$root/lib.rs", "fn base() {}\n" + (0 until 1500).joinToString("") { "fn added${it.toString().padStart(4, '0')}() {}\n" })
            val out = GraphDiffs.call(rt, JsonObject(emptyMap()), solo(root))
            assertTrue(out.startsWith("graph diff HEAD -> working (tags)\n0 changed · 1500 added"), out.take(300))
            assertTrue(out.contains("more, expand "), out.takeLast(300))
            assertFalse(out.contains("added1499"))
        }

    @Test fun jsonCarriesEveryRowAndTheCallers() =
        linked("t32918-json") { rt, dir, scope ->
            writeString("$dir/b/lib.rs", "fn shared(x: i32) {}\n")
            val v = Json.parseToJsonElement(GraphDiffs.run(rt, scope, DiffQuery(json = true))).jsonObject
            val b = v["projects"]!!.jsonArray.map { it.jsonObject }.first { it["project"]!!.jsonPrimitive.content == "b" }
            val c = b["changed"]!!.jsonArray[0].jsonObject
            assertEquals("shared", c["name"]!!.jsonPrimitive.content)
            assertEquals(true, c["signature_changed"]!!.jsonPrimitive.boolean)
            assertEquals("[a] lib.rs a_caller d1", c["callers"]!!.jsonArray[0].jsonPrimitive.content)
        }

    @Test fun aRefForOneProjectLeavesTheOthersOnTheSharedRef() =
        linked("t32929-from") { rt, dir, scope ->
            val b = "$dir/b"
            writeString("$b/lib.rs", "fn shared(x: i32) {}\n")
            commit(b)

            fun from(vararg f: String) = GraphDiffs.run(rt, scope, DiffQuery(f.toList()))
            assertTrue(from("HEAD").contains("no graph changes"))
            val out = from("b:HEAD~1")
            assertTrue(out.startsWith("graph diff HEAD -> working"), out)
            assertTrue(out.contains("[b from HEAD~1] 1 changed"), out)
            assertFalse(out.contains("[a] 1 changed"), out)
            val id = rt.store.projectByRoot(b)!!.id
            assertTrue(from("$id:HEAD~1").contains("[b from HEAD~1] 1 changed"))
            assertTrue(assertFails { from("HEAD", "main") }.message!!.contains("twice"))
        }

    @Test fun filesNoGrammarReadsAreListedAsChangedAndNotAnalysed() =
        cx("t32929-unread", { c -> c.copy(plugins = c.plugins.copy(graph = c.plugins.graph.copy(exclude = listOf("vendor/")))) }) { rt, dir ->
            val root = project(dir, "p", listOf("lib.rs" to "fn f() {}\n", "notes.md" to "one\n", "bad.rs" to "fn g() {}\n", "vendor/x.md" to "one\n"))
            writeString("$root/notes.md", "two\n")
            writeString("$root/vendor/x.md", "two\n")
            writeBytes("$root/bad.rs", byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0))
            val out = ask(rt, solo(root), "HEAD")
            assertTrue(out.contains("2 changed, not analysed"), out)
            assertTrue(out.contains("  notes.md  (no grammar)"), out)
            assertTrue(out.contains("  bad.rs  (not parsed)"), out)
            assertFalse(out.contains("vendor"), out)
            assertFalse(out.contains("no graph changes"), out)
        }

    private fun save(
        rt: Runtime,
        scope: List<Member>,
        dir: String,
        level: ExportLevel,
    ): String {
        val path = "$dir/saved.json"
        writeString(path, ExportView.run(rt, scope, ExportQuery(level, redact = false)))
        return path
    }

    private fun against(
        rt: Runtime,
        scope: List<Member>,
        file: String,
        from: List<String> = emptyList(),
    ) = GraphDiffs.run(rt, scope, DiffQuery(from, export = file))

    @Test fun aSavedExportListsWhatAppearedAndVanishedAndTheLinks() =
        linked("t32929-export") { rt, dir, scope ->
            val file = save(rt, scope, dir, ExportLevel.Symbols)
            assertTrue(against(rt, scope, file).contains("no graph changes"))
            writeString("$dir/b/lib.rs", "fn shared(x: i32) {}\nfn fresh() {}\n")
            writeString("$dir/a/lib.rs", "fn other() {}\n")
            val ids = scope.map { rt.store.projectByRoot(it.root)!!.id }
            rt.store.unlinkProjects(ids[0], ids[1])
            val out = against(rt, scope, file)
            assertTrue(out.contains("graph diff export "), out)
            assertTrue(out.contains("note: against an export"), out)
            assertTrue(out.contains("added (1)\n  function fresh"), out)
            assertTrue(out.contains("removed (1)\n  function a_caller"), out)
            assertTrue(out.contains("added (1)\n  function other"), out)
            assertFalse(out.contains("changed ("), out)
            assertTrue(out.contains("links removed (1)\n  a -> b (manual)"), out)
            assertFalse(out.contains("links added"), out)
        }

    @Test fun anExportThatCannotBeComparedSaysWhy() =
        linked("t32929-export-errors") { rt, dir, scope ->
            val out = against(rt, scope, save(rt, scope, dir, ExportLevel.Overview))
            assertTrue(out.contains("only links are compared"), out)
            assertTrue(out.contains("no graph changes"), out)
            val symbols = save(rt, scope, dir, ExportLevel.Symbols)
            assertTrue(assertFails { against(rt, scope, symbols, listOf("HEAD")) }.message!!.contains("drop --from and --to"))
            assertTrue(assertFails { against(rt, scope, "$dir/none.json") }.message!!.contains("none.json"))
        }

    private fun pageReq(
        rt: Runtime,
        dir: String,
        export: DiffExport? = null,
    ) = DiffRequest(
        rt.store
            .projectByRoot("$dir/a")!!
            .id
            .toString(),
        export = export,
    )

    @Test fun thePageGetsTheReportTheJsonPrints() =
        linked("t32935-page") { rt, dir, scope ->
            writeString("$dir/b/lib.rs", "fn shared(x: i32) {}\nfn fresh() {}\n")
            val r = GraphDiffs.page(rt, pageReq(rt, dir))
            val b = r.projects.first { it.project == "b" }
            assertEquals("shared", b.changed[0].name)
            assertEquals(true, b.changed[0].signatureChanged)
            assertEquals(listOf("[a] lib.rs a_caller d1"), b.changed[0].callers)
            assertEquals("fresh", b.added[0].name)
            val cli = GraphDiffs.run(rt, scope, DiffQuery(json = true))
            assertEquals(Json.parseToJsonElement(cli), Json { encodeDefaults = true }.encodeToJsonElement(DiffReport.serializer(), r))
        }

    @Test fun thePageSendsAnExportAsTextAndNeverAPath() =
        linked("t32935-export") { rt, dir, scope ->
            val file = save(rt, scope, dir, ExportLevel.Symbols)
            writeString("$dir/b/lib.rs", "fn shared() {}\nfn fresh() {}\n")
            val text = readToString(file)
            val r = GraphDiffs.page(rt, pageReq(rt, dir, DiffExport("saved.json", text)))
            assertEquals("export saved.json", r.from)
            assertEquals(
                "fresh",
                r.projects
                    .first { it.project == "b" }
                    .added[0]
                    .name,
            )
            assertTrue(r.notes.contains("only added and removed symbols"), r.notes)
            val e = assertFails { GraphDiffs.page(rt, pageReq(rt, dir, DiffExport("x", file))) }
            assertTrue(e.message!!.contains("is not a graph export"), e.message)
            assertFails { GraphDiffs.page(rt, pageReq(rt, dir, DiffExport("saved.json", text)).copy(from = listOf("HEAD"))) }
        }

    @Test fun aLongReportIsCutAndCounted() =
        linked("t32935-cap") { rt, dir, _ ->
            writeString("$dir/b/lib.rs", "fn shared() {}\nfn one() {}\nfn two() {}\n")
            val b =
                GraphDiffs
                    .page(rt, pageReq(rt, dir))
                    .capped(1)
                    .projects
                    .first { it.project == "b" }
            assertEquals(1 to 1, b.added.size to b.more)
        }
}
