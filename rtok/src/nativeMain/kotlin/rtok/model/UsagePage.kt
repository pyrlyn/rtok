// Port of src/model/mod.rs `UsagePage`, `usage_page` and `read_usage_page` (T358.5): what `rtok agents usage`
// reports, read off the tick, as the screen text (tui, status line) and the same rows as data (web tables).
package rtok.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agents.Usage
import rtok.agents.UsageText
import rtok.config.Config
import kotlin.time.Duration.Companion.seconds

data class UsagePage(
    val text: String,
    val report: JsonObject?,
) {
    fun toJson(): JsonElement = JsonObject(mapOf("text" to JsonPrimitive(text), "report" to (report ?: JsonNull)))

    companion object {
        /** `USAGE_TTL`: session logs only move when a turn ends. */
        val TTL = 120.seconds

        val READING = UsagePage("reading usage…\n", null)

        private val cache = Background<UsagePage>("web-usage")

        /** The last read, or [READING] before the first one lands. */
        fun get(
            cfg: Config,
            read: (Config) -> UsagePage = ::read,
        ): UsagePage = cache.get(TTL) { read(cfg) } ?: READING

        /** One synchronous read; a store that will not open or a bad `[agents.usage]` value is the page's text. */
        fun read(cfg: Config): UsagePage =
            runCatching { Usage.openReport(cfg) }.fold(
                { UsagePage(UsageText.text(it), UsageText.json(it)) },
                { UsagePage("usage did not answer this tick: ${it.message ?: it}\n", null) },
            )

        internal fun forget() = cache.forget()
    }
}
