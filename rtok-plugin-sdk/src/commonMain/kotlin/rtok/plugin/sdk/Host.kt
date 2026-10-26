// Port of crates/rtok-plugin-sdk/src/host.rs (subset).
package rtok.plugin.sdk

/** Text classes for the token estimator. */
enum class Class { Code, Prose, Json, Cjk }

/** One full-text search hit from Notes.searchNotes. */
data class NoteHit(val id: Int, val title: String, val snippet: String)
