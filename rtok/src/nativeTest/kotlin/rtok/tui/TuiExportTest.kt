// Port of the tests of src/tui/exporter.rs (T329.41): the Graph tab's `e` writes the bytes `rtok graph export` writes
// for the same scope; `v` views a saved export and writes nothing. Everything lives in a temp dir.
@file:Suppress("MagicNumber", "TooManyFunctions")

package rtok.tui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rtok.config.Config
import rtok.model.GraphPage
import rtok.plugin.Runtime
import rtok.plugins.graph.ExportFormat
import rtok.plugins.graph.ExportImage
import rtok.plugins.graph.ExportLevel
import rtok.plugins.graph.ExportQuery
import rtok.plugins.graph.ExportView
import rtok.plugins.graph.Scope
import rtok.store.Origin
import rtok.store.registerProject
import rtok.support.installDb
import rtok.support.tmpDir
import rtok.sys.canonicalize
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.readBytes
import rtok.sys.readDir
import rtok.sys.removeDirAll
import rtok.sys.writeString
import tui.Buffer
import tui.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SRC = "pub fn caller() {\n    callee();\n}\n\npub fn callee() {}\n"

class TuiExportTest {
    private class Fixture(
        val base: String,
        val cfg: Config,
        val app: TuiApp,
    )

    /** A project `p` in a temp dir and its registry row; the home sits beside it. */
    private fun fixture(
        tag: String,
        register: Boolean = true,
        body: (Fixture) -> Unit,
    ) {
        val dir = canonicalize(tmpDir("tui-export-$tag"))!!
        try {
            installDb()
            val cfg = Config().rebasePaths("$dir/home").also { it.home = "$dir/home" }
            val base = "$dir/out"
            createDirAll("$base/p")
            writeString("$base/p/lib.rs", SRC)
            if (register) Runtime.open(cfg, "t32941-fixture").use { it.store.registerProject("$base/p", Origin.Manual) }
            val app = TuiApp("graph", TuiActions(cfg))
            app.snapshot = JsonObject(mapOf("projects" to GraphPage.projectsJson(cfg)))
            body(Fixture(base, cfg, app))
        } finally {
            removeDirAll(dir)
        }
    }

    private fun press(
        a: TuiApp,
        k: Key,
    ) = assertTrue(a.key(k) != Effect.QUIT, "$k quit")

    private fun type(
        a: TuiApp,
        text: String,
    ) = text.forEach { press(a, Key.Char(it.toString())) }

    private fun screen(
        a: TuiApp,
        cols: Int = 100,
    ) = Buffer(cols, 70).also { draw(it, a, { "00:00:00" }) }.lines().joinToString("\n")

    /** Opens the form, picks format, level and redact with Space, names the file and writes it. */
    private fun exportTo(
        a: TuiApp,
        steps: Triple<Int, Int, Int>,
        file: String,
    ) {
        press(a, Key.Char("e"))
        for ((n, up) in listOf(steps.first to 3, steps.second to 2, steps.third to 1)) {
            repeat(up) { press(a, Key.Up) }
            repeat(n) { press(a, Key.Char(" ")) }
            repeat(up) { press(a, Key.Down) }
        }
        type(a, file)
        press(a, Key.Enter)
    }

    private fun cliBytes(
        cfg: Config,
        format: ExportFormat,
        level: ExportLevel,
    ): ByteArray =
        Runtime.open(cfg, "t32941-cli").use { rt ->
            ExportView.render(rt, Scope.resolve(rt.store, "1", "."), ExportQuery(level, null, 2, redact = true, pretty = true), ExportImage(format))
        }

    /** The export time to the second and the index time are blanked on both sides. */
    private fun blank(b: ByteArray) = Regex("""\d{4}-\d\d-\d\d \d\d:\d\d:\d\d UTC|"(exported|indexed)_at": \d+""").replace(b.decodeToString(), "<time>")

    @Test fun theKeyWritesTheBytesTheCliWritesForJsonAndSvg() =
        fixture("bytes") { f ->
            // The first symbols export indexes the project; after it every run sees the same index.
            cliBytes(f.cfg, ExportFormat.Json, ExportLevel.Symbols)
            exportTo(f.app, Triple(0, 0, 0), "${f.base}/a.json")
            assertTrue("wrote" in screen(f.app), screen(f.app))
            assertEquals(blank(cliBytes(f.cfg, ExportFormat.Json, ExportLevel.Overview)), blank(readBytes("${f.base}/a.json")))
            // The result stays up; `e` opens the form again with the last choices.
            press(f.app, Key.Char("e"))
            press(f.app, Key.Esc)
            exportTo(f.app, Triple(1, 1, 0), "${f.base}/a.svg")
            val svg = readBytes("${f.base}/a.svg")
            assertTrue(svg.decodeToString().startsWith("<svg"))
            assertEquals(blank(cliBytes(f.cfg, ExportFormat.Svg, ExportLevel.Symbols)), blank(svg))
            // PNG is refused the way `rtok graph export --format png` refuses it in this build.
            exportTo(f.app, Triple(1, 0, 0), "${f.base}/a.png")
            assertTrue("✕" in screen(f.app) && "png" in screen(f.app), screen(f.app))
            assertFalse(exists("${f.base}/a.png"))
        }

    @Test fun redactionIsOnUntilTheUserTurnsItOff() =
        fixture("redact") { f ->
            val root = { file: String ->
                val v = Json.parseToJsonElement(readBytes(file).decodeToString()).jsonObject
                v["meta"]!!.jsonObject["redacted"]!!.jsonPrimitive.content to
                    v["projects"]!!
                        .jsonArray[0]
                        .jsonObject["root"]!!
                        .jsonPrimitive.content
            }
            exportTo(f.app, Triple(0, 0, 0), "${f.base}/on.json")
            val (on, onRoot) = root("${f.base}/on.json")
            assertEquals("true", on)
            assertFalse(onRoot.startsWith("/"), onRoot)
            exportTo(f.app, Triple(0, 0, 1), "${f.base}/off.json")
            assertTrue("redaction was off" in screen(f.app), screen(f.app))
            val (off, offRoot) = root("${f.base}/off.json")
            assertEquals("false", off)
            assertTrue(offRoot.startsWith("/") && offRoot.endsWith("p"), offRoot)
        }

    @Test fun typedKeysStayInTheFileNameAndAnEmptyNameWritesNothing() =
        fixture("typing") { f ->
            val page = screen(f.app, 260)
            assertTrue("e export" in page && "v view saved export" in page, page)
            press(f.app, Key.Char("e"))
            type(f.app, "r?qe3")
            assertTrue("r?qe3▏" in screen(f.app))
            assertFalse(f.app.help)
            assertEquals("graph", f.app.page)
            repeat(5) { press(f.app, Key.Backspace) }
            press(f.app, Key.Enter)
            assertTrue("name the file to write" in screen(f.app))
            press(f.app, Key.Esc)
            assertTrue("e export" in screen(f.app, 260))
            assertEquals(listOf("p"), readDir(f.base).map { it.substringAfterLast('/') }, "only the project dir")
            assertEquals(Effect.QUIT, f.app.key(Key.Esc), "the shell's Esc is back")
        }

    @Test fun aFailedWriteIsALineNotACrash() =
        fixture("fail") { f ->
            exportTo(f.app, Triple(0, 0, 0), "${f.base}/no-such-dir/x.json")
            assertTrue('✕' in screen(f.app))
        }

    @Test fun theSavedExportViewShowsTheFileAndWritesNothing() =
        fixture("view") { f ->
            val file = "${f.base}/saved.json"
            exportTo(f.app, Triple(0, 1, 0), file)
            val before = readBytes(file)
            val names = readDir(f.base).sorted()
            press(f.app, Key.Char("v"))
            type(f.app, "r?q3")
            assertTrue("r?q3▏" in screen(f.app))
            repeat(4) { press(f.app, Key.Backspace) }
            type(f.app, file)
            press(f.app, Key.Enter)
            val shown = screen(f.app)
            listOf("viewing export from", "(read-only)", "projects (1)", "nodes (", "caller", "callee").forEach { assertTrue(it in shown, "lacks $it:\n$shown") }
            assertTrue(before.contentEquals(readBytes(file)), "the file is untouched")
            assertEquals(names, readDir(f.base).sorted(), "nothing was created")
            writeString("${f.base}/junk.json", "{}")
            press(f.app, Key.Char("v"))
            type(f.app, "${f.base}/junk.json")
            press(f.app, Key.Enter)
            assertTrue('✕' in screen(f.app), "a file that is not an export is refused")
        }

    @Test fun noProjectRowMeansNoExportButASavedFileCanStillBeViewed() =
        fixture("none", register = false) { f ->
            press(f.app, Key.Char("e"))
            assertTrue("no project to export" in screen(f.app))
            press(f.app, Key.Char("v"))
            assertTrue("view the saved export" in screen(f.app))
        }
}
