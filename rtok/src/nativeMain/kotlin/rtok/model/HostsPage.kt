// Port of src/model/mod.rs `hosts_page_text` (T231): `rtok agents list`'s blocks, probed off the tick.
package rtok.model

import rtok.agents.AgentsList
import rtok.config.Config
import kotlin.time.Duration.Companion.seconds

object HostsPage {
    /** `DOCTOR_SNAPSHOT_TTL`: how long a host probe may be reused. */
    val TTL = 30.seconds

    const val PROBING = "probing hosts…\n"

    /** The junk section before its first measurement lands. */
    const val JUNK_PENDING = "measuring folders…\n"

    private val hosts = Background<String>("web-hosts")

    /** The last probe's blocks plus the junk section, or [PROBING] before the first probe lands. */
    fun text(
        cfg: Config,
        probe: (Config) -> String = { AgentsList.list(it) },
        junk: (Config) -> String? = { JunkPage.get(it)?.text },
    ): String {
        val blocks = hosts.get(TTL) { probe(cfg) } ?: return PROBING
        return "$blocks\njunk\n${junk(cfg) ?: JUNK_PENDING}"
    }

    internal fun forget() = hosts.forget()
}
