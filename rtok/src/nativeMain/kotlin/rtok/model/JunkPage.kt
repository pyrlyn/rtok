// Port of src/model/mod.rs `junk_page` and src/agents/junk_web.rs `JunkCard` (T330.1, T330.7): the junk section of
// the Hosts page as text (tui) and as the card (web), from one cached report on the worktrees TTL.
package rtok.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agents.Junk
import rtok.agents.JunkList
import rtok.agents.JunkReport
import rtok.agents.JunkView
import rtok.config.Config

object JunkPage {
    class Page(
        val text: String,
        val card: JsonObject,
    )

    private val cache = Background<Page>("web-junk")

    /** The last report's text and card, or null while the first measurement runs. */
    fun get(
        cfg: Config,
        read: (Config) -> Page = ::read,
    ): Page? = cache.get(WorktreesPage.TTL) { read(cfg) }

    internal fun read(cfg: Config): Page {
        val report = Junk.report(cfg)
        return Page(JunkList.toList(report, JunkView.of(cfg)), card(report))
    }

    /** `forget_junk`: after a clear the sizes would otherwise stay for up to the TTL. */
    fun forget() = cache.forget()

    /** `JunkCard::from`: rtok and every host with something to clear; a host with only folders is a page block. */
    fun card(report: JunkReport): JsonObject =
        JsonObject(
            mapOf(
                "agents" to
                    JsonArray(
                        report.agents.filter { !it.host || it.kinds.isNotEmpty() }.map { a ->
                            JsonObject(
                                mapOf(
                                    "name" to JsonPrimitive(a.name),
                                    "total_bytes" to JsonPrimitive(a.totalBytes),
                                    "kinds" to
                                        JsonArray(
                                            a.kinds.map { k ->
                                                JsonObject(
                                                    mapOf(
                                                        "kind" to JsonPrimitive(k.kind),
                                                        "class" to JsonPrimitive(k.cls),
                                                        "items" to JsonPrimitive(k.items),
                                                        "size_bytes" to JsonPrimitive(k.sizeBytes),
                                                    ),
                                                )
                                            },
                                        ),
                                    "freed_default_bytes" to JsonPrimitive(a.freedDefaultBytes),
                                    "freed_review_bytes" to JsonPrimitive(a.freedReviewBytes),
                                ),
                            )
                        },
                    ),
                "total_bytes" to JsonPrimitive(report.totalBytes),
                "freed_default_bytes" to JsonPrimitive(report.freedDefaultBytes),
                "freed_review_bytes" to JsonPrimitive(report.freedReviewBytes),
            ),
        )
}
