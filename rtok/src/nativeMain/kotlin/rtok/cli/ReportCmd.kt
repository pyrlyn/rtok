// Port of src/cli/report.rs: `rtok report` picks the renderer and the sink; every number was already computed by
// the model (D24). `--format md|html|pdf`, `--out`, `--since` and `--ai` merge into `[report]` only when typed (D12).
package rtok.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.config.Config
import rtok.config.parseSince
import rtok.report.Ai
import rtok.report.Html
import rtok.report.Markdown
import rtok.report.Pdf
import rtok.report.ReportDocument

private val REPORT_FORMATS = listOf("md", "html", "pdf")

/** The `[report]` flag layer: only typed flags set a key, so an explicit `--format md` beats the config (T587). */
internal fun reportFlags(
    format: String?,
    out: String?,
    since: String?,
    ai: Boolean,
): JsonObject? {
    val report =
        buildJsonObject {
            format?.let { put("format", it) }
            out?.let { put("out", it) }
            since?.let { put("since", it) }
            if (ai) put("ai", true)
        }
    return if (report.isEmpty()) null else buildJsonObject { put("report", report) }
}

/** `emit`: the body to stdout, or to `out` followed by its path on stdout. */
private fun Invocation.emit(
    out: String,
    body: ByteArray,
) {
    if (out.isEmpty()) {
        io.writeBytes(body)
    } else {
        rtok.sys.writeBytes(out, body)
        io.println(out)
    }
}

val REPORT =
    CommandSpec(
        "report",
        "The operator model as one document: Markdown, HTML and PDF",
        options =
            listOf(
                OptSpec.Value("format", "Output format", "FORMAT"),
                OptSpec.Value("out", "Write to this path instead of stdout", "PATH"),
                OptSpec.Value("since", "How far back the report reads (`30d`, `24h`)", "SINCE"),
                OptSpec.Flag("ai", "Model-shaped rendering of the same document instead of `--format`"),
            ),
    ) { inv ->
        val format = inv.value("format")
        if (format != null && format !in REPORT_FORMATS) {
            throw CliFailure("invalid value '$format' for '--format <FORMAT>'\n  [possible values: ${REPORT_FORMATS.joinToString(", ")}]", ExitCode.USAGE)
        }
        val since = inv.value("since")
        // As for `stats`: parsed before it merges, so a later parse error comes from the config or the environment.
        since?.let(::parseSince)
        val cfg = Config.loadWith(inv.configFile, reportFlags(format, inv.value("out"), since, inv.flag("ai")))
        val doc = ReportDocument.build(cfg, Config.homeDir(), inv.configFile)
        val out = cfg.report.out
        when {
            cfg.report.ai -> inv.emit(out, Ai.render(doc, cfg).encodeToByteArray())
            cfg.report.format == "md" -> inv.emit(out, Markdown.render(doc).encodeToByteArray())
            cfg.report.format == "html" -> inv.emit(out, Html.render(doc).encodeToByteArray())
            cfg.report.format == "pdf" -> inv.emit(out, Pdf.render(doc))
            else -> throw CliFailure("--format ${cfg.report.format} is unknown (md, html, pdf)")
        }
    }
