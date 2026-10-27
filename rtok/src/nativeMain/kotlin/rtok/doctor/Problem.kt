// Port of the `Problem` finding of src/doctor/hooks.rs: one finding of a doctor config check, shared by every T331
// detector. The JSON shape (`doctor --json`) lands with the doctor command.
package rtok.doctor

data class Problem(
    /**
     * `broken-hook`, `suspect-hook`, `unverified-hook`, `duplicate-hook`, `duplicate-mcp`, `conflicting-mcp`,
     * `own-mcp`, `stale-plugin` or `unreadable-config`.
     */
    val kind: String,
    val agent: String,
    /** The config file the entry lives in. */
    val source: String,
    /** The key path of the entry inside [source]. */
    val path: String = "",
    val event: String = "",
    val matcher: String? = null,
    /** The command as written, never expanded. */
    val command: String = "",
    val detail: String = "",
    /** Whether `doctor --fix` may remove it (T331.5). */
    val fixable: Boolean = false,
    /** Copies of one duplicate share a [group]; [keep] marks the copy to keep (T331.3). */
    val group: Int? = null,
    val keep: Boolean = false,
)
