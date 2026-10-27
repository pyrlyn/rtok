// Port of tests/report_ai.rs (T22.4), the CLI half of tests/report_pdf.rs (T22.3) and the empty-store, `--out` and
// T587 checks of tests/report.rs: `rtok report` over a seeded temp store. HOME points into the temp home and the
// base-URL variables are unset, so the doctor section probes nothing outside it.
@file:Suppress("MagicNumber")

package rtok.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import rtok.config.Config
import rtok.config.envVar
import rtok.plugin.sdk.Class
import rtok.plugin.sdk.Measurement
import rtok.store.Store
import rtok.store.insertCall
import rtok.store.insertMeasurement
import rtok.store.insertUsage
import rtok.store.setCallMs
import rtok.store.upsertSession
import rtok.sys.createDirAll
import rtok.sys.readBytes
import rtok.sys.readToString
import rtok.sys.writeString

private val HEADS = listOf("Window", "Savings", "Calls", "Cache", "Expand", "Config", "Doctor", "Recommendations")
private val ISOLATED = listOf("HOME", "ANTHROPIC_BASE_URL", "OPENAI_BASE_URL")

/** [withHome] plus HOME = the temp home and no base-URL overrides; every variable is restored after. */
private fun <T> withReportHome(
    tag: String,
    body: (String) -> T,
): T =
    withHome(tag) { home ->
        val saved = ISOLATED.associateWith { envVar(it) }
        ISOLATED.forEach { platform.posix.unsetenv(it) }
        platform.posix.setenv("HOME", home, 1)
        try {
            body(home)
        } finally {
            saved.forEach { (k, v) -> if (v == null) platform.posix.unsetenv(k) else platform.posix.setenv(k, v, 1) }
        }
    }

private fun ok(vararg args: String): String {
    val r = cli(*args)
    r.err shouldBe ""
    r.code shouldBe 0
    return r.out
}

private fun tokens(s: String) = rtok.estimate(s, Class.Prose, Config().estimator)

/** report_ai.rs `seed`: two timed hook calls, cmd 100→40 and archive 200→50 estimates, one usage row, two hooks. */
private fun seed(home: String) {
    createDirAll("$home/.claude")
    writeString(
        "$home/.claude/settings.json",
        """{"hooks":{"PreToolUse":[{"hooks":[{"type":"command","command":"rtok hook PreToolUse"}]}],"PostToolUse":[{"hooks":[{"type":"command","command":"rtok hook PostToolUse"}]}]}}""",
    )
    rtok.support.installDb()
    val store = Store.open(Config.loadFrom(home).core.dbPath)
    store.upsertSession("s1", null, null, null, "proxy")
    for (ms in listOf(4.0, 2.0)) store.setCallMs(store.insertCall("s1", "hook", "hook", null, null, null, null, null), ms)
    store.insertMeasurement("s1", Measurement("cmd", "filter", 100u, 40u, 100u, 40u, null))
    store.insertMeasurement("s1", Measurement("archive", "pointer", 100u, 40u, 200u, 50u, null))
    val id = store.insertCall("s1", "proxy", "api_request", null, null, null, null, null)
    store.insertUsage("s1", "m", "anthropic", 100, 0, 0, 5, id)
}

/** Latin-1 view of a file: one char per byte, so `(Window` and friends are found in the PDF bytes. */
private fun latin1(path: String) = readBytes(path).map { (it.toInt() and 0xFF).toChar() }.joinToString("")

private fun positions(
    hay: String,
    needle: String,
) = generateSequence(hay.indexOf(needle).takeIf { it >= 0 }) { at -> hay.indexOf(needle, at + 1).takeIf { it >= 0 } }.toList()

class ReportCliSpec :
    FunSpec({
        tags(Cli)
        test("--ai is measurably smaller than md") {
            withReportHome("report-ai-smaller") { home ->
                seed(home)
                val (md, ai) = ok("report") to ok("report", "--ai")
                (tokens(ai) < tokens(md)) shouldBe true
                ai shouldNotContain "| ---"
            }
        }
        test("--ai carries the section set with md numbers") {
            withReportHome("report-ai-sections") { home ->
                seed(home)
                val (md, ai) = ok("report") to ok("report", "--ai")
                ai.lines().filter { it.startsWith('#') } shouldBe HEADS.map { "#${it.lowercase()}" }
                HEADS.forEach { md shouldContain "## $it" }
                for (want in listOf("total_saved=210tok rows=2", "hooks 2", "dropped: none", "units:")) ai shouldContain want
                val tail = ai.substring(ai.indexOf("#recommendations"))
                ("no recommendations." in tail || "1. [" in tail) shouldBe true
            }
        }
        test("a tight budget names the dropped sections") {
            withReportHome("report-ai-budget") { home ->
                seed(home)
                writeString("$home/config.toml", "[report]\nbudget_tokens = 100\n")
                val ai = ok("report", "--ai")
                val dropped =
                    ai
                        .lines()
                        .first { it.startsWith("dropped: ") }
                        .substring(9)
                        .split(',')
                dropped shouldNotBe listOf("none")
                dropped.forEach { ai shouldNotContain "#$it\n" }
            }
        }
        test("an empty store --ai says so") {
            withReportHome("report-ai-empty") {
                val ai = ok("report", "--ai")
                for (want in listOf("dropped: none", "no rows.", "no recommendations.", "hooks 0")) ai shouldContain want
            }
        }
        test("the pdf has the html headings in order and the charts") {
            withReportHome("report-pdf") { home ->
                seed(home)
                val html = ok("report", "--format", "html")
                HEADS.forEach { html shouldContain ">$it</h2>" }
                val path = "$home/report.pdf"
                ok("report", "--format", "pdf", "--out", path).trim() shouldBe path
                val pdf = latin1(path)
                pdf shouldStartWith "%PDF-"
                pdf.takeLast(8) shouldContain "%%EOF"
                positions(pdf, "(Contents)").isNotEmpty() shouldBe true
                (positions(pdf, "/Type/Page").size - positions(pdf, "/Type/Pages").size >= 2) shouldBe true
                val first = HEADS.map { positions(pdf, "($it").first() }
                val last = HEADS.map { positions(pdf, "($it").last() }
                first shouldBe first.sorted()
                last shouldBe last.sorted()
                listOf("(cmd)", "(hook)", "(saved tokens per plugin)").forEach { positions(pdf, it).isNotEmpty() shouldBe true }
            }
        }
        test("an empty store pdf keeps all headings") {
            withReportHome("report-pdf-empty") { home ->
                val path = "$home/empty.pdf"
                ok("report", "--format", "pdf", "--out", path)
                val pdf = latin1(path)
                pdf shouldStartWith "%PDF-"
                HEADS.forEach { positions(pdf, "($it").isNotEmpty() shouldBe true }
                positions(pdf, "(No rows in window.)").isNotEmpty() shouldBe true
            }
        }
        test("an empty store says so rather than printing zeros") {
            withReportHome("report-empty") {
                val out = ok("report")
                for (want in listOf("## Recommendations", "**No rows in window.** The store has no rows to report.", "No recommendations.", "| calls | 0 of 0 | window (30d) |", "| report.format | md | default |", "hooks 0")) {
                    out shouldContain want
                }
                out shouldNotContain "| cmd |"
                out shouldNotContain "| hook |"
            }
        }
        test("--out writes the file and prints its path") {
            withReportHome("report-out") { home ->
                seed(home)
                val path = "$home/report.md"
                ok("report", "--out", path).trim() shouldBe path
                val written = readToString(path)
                written shouldStartWith "# rtok report"
                written shouldContain "| cmd | 1 | 100 | 40 | 60 |"
            }
        }
        test("an explicit --format md overrides the configured format (T587)") {
            withReportHome("report-format-md") { home ->
                val cfg = "$home/rtok.toml"
                for (configured in listOf("html", "pdf")) {
                    writeString(cfg, "[report]\nformat = \"$configured\"\n")
                    val explicit = ok("--config", cfg, "report", "--format", "md")
                    (explicit.trimStart().startsWith('#') && "<html" !in explicit) shouldBe true
                    ok("--config", cfg, "report").trimStart().startsWith('#') shouldBe false
                }
            }
        }
        test("an unknown --format is a usage error") {
            withReportHome("report-format-bad") {
                cli("report", "--format", "docx").code shouldBe 2
            }
        }
    })
