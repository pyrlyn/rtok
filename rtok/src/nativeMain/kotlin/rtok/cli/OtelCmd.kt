// Port of src/cli/otel.rs: `rtok otel flush [--coalesce]` and `rtok otel status [--json]`.
package rtok.cli

import kotlinx.serialization.json.JsonObject
import rtok.agents.AgentsJson
import rtok.otel.Export
import rtok.otel.FlushTrace
import rtok.plugin.Runtime

val OTEL =
    CommandSpec(
        "otel",
        "OpenTelemetry export (`rtok otel flush | status`)",
        subcommands =
            listOf(
                CommandSpec(
                    "flush",
                    "Post rows past the watermarks to the endpoint, once",
                    options =
                        listOf(
                            OptSpec.Flag(
                                "coalesce",
                                "Hook-spawned only: coalesce concurrent hook flushes to one running + one queued process",
                            ),
                        ),
                ) { inv ->
                    val rep =
                        Runtime.open(inv.loadConfig(), "otel").use { cx ->
                            // The coalesced run is the hook's detached child (T143); a manual flush always runs.
                            if (inv.flag("coalesce")) FlushTrace.start().use { Export.flushCoalesced(cx) } else Export.flush(cx)
                        }
                    inv.io.println(rep.toString())
                },
                CommandSpec(
                    "status",
                    "Endpoint, watermarks, pending rows, last exporter log line",
                    options = listOf(OptSpec.Flag("json", "JSON instead of the table")),
                ) { inv ->
                    Runtime.open(inv.loadConfig(), "otel").use { cx ->
                        if (inv.flag("json")) {
                            inv.io.println(AgentsJson.PRETTY.encodeToString(JsonObject.serializer(), Export.statusJson(cx)))
                        } else {
                            inv.io.out(Export.status(cx))
                        }
                    }
                },
            ),
    )
