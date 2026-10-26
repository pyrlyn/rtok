// Port of src/plugins/checkpoint.rs (model and render) — PreCompact checkpoint + compact restore (plan T2.5).
package rtok.plugins.checkpoint

/** What the session did to a file. Declaration order is the render order (T375). */
enum class Action(val word: String) {
    Edited("edited"), Created("created"), Deleted("deleted"), Read("read");
    companion object { fun fromWord(w: String) = entries.firstOrNull { it.word == w } }
}

/** `offset`, and `offset + limit - 1` when a limit was given, of the last ranged `Read`. */
data class Lines(val from: ULong, val to: ULong?)

data class PathEntry(val path: String, val action: Action, val lines: Lines? = null) {
    fun render(out: StringBuilder) {
        out.append("path ").append(path).append(" (").append(action.word)
        lines?.let { out.append(" ${it.from}-"); it.to?.let(out::append) }
        out.append(")\n")
    }

    companion object {
        /** The text after `path ` in a stored note; a bare path (pre-T375) or a non-action suffix decodes as a read. */
        fun parse(rest: String): PathEntry {
            val plain = PathEntry(rest, Action.Read)
            if (!rest.endsWith(')')) return plain
            val body = rest.dropLast(1); val at = body.lastIndexOf(" (")
            if (at < 0) return plain
            val tail = body.substring(at + 2); val sp = tail.indexOf(' ')
            val word = if (sp < 0) tail else tail.substring(0, sp); val range = if (sp < 0) null else tail.substring(sp + 1)
            val action = Action.fromWord(word) ?: return plain
            val lines = range?.let { r -> val dash = r.indexOf('-'); if (dash < 0) null else r.substring(0, dash).toRustU64()?.let { Lines(it, r.substring(dash + 1).toRustU64()) } }
            return PathEntry(body.substring(0, at), action, lines)
        }
    }
}

/** Rust `u64::from_str`: an optional `+`, then ASCII digits. */
internal fun String.toRustU64(): ULong? = removePrefix("+").takeIf { s -> s.isNotEmpty() && s.all { it in '0'..'9' } }?.toULongOrNull()

/** Parsed compact snapshot. */
data class Checkpoint(
    val prompts: List<String> = emptyList(),
    /** Changed files first, then read ones, each group by path (byte-stable, T375). */
    val paths: List<PathEntry> = emptyList(),
    val errors: List<String> = emptyList(),
    /** Skills injected before compaction, `(name, body bytes)` per invocation (T62.2). */
    val skills: List<Pair<String, ULong>> = emptyList(),
    /** Archive ids of the session's live-window tool results, newest first (T58.2), with `(tool, bytes)`. */
    val ids: List<String> = emptyList(),
    internal val idMeta: List<Pair<String, ULong>> = emptyList(),
    /** Typed / host-written user-text records over the whole transcript (T419); never rendered. */
    val typed: ULong = 0u,
    val skipped: ULong = 0u,
) {
    fun render(): String = buildString {
        append("checkpoint\n")
        prompts.forEach { append("- ").append(it).append('\n') }
        if (skills.isNotEmpty()) {
            append("skills loaded before compaction: ")
            append(skills.joinToString(", ") { (n, b) -> "$n (${kib1(b)} KB)" })
            append(" — re-invoke only what the next step needs\n")
        }
        paths.forEach { it.render(this) }
        errors.forEach { append("err ").append(it).append('\n') }
        ids.zip(idMeta).forEach { (id, m) -> append("id ").append(id).append(' ').append(m.first).append(' ').append(m.second).append('\n') }
    }
}

/** Rust `{:.1}` of `bytes as f64 / 1024.0`: exact, ties to even. */
internal fun kib1(bytes: ULong): String {
    val n = bytes * 10uL; var q = n / 1024uL; val r = n % 1024uL
    if (r * 2uL > 1024uL || (r * 2uL == 1024uL && q % 2uL == 1uL)) q++
    return "${q / 10uL}.${q % 10uL}"
}

/** Rust `u64::saturating_add`. */
internal fun sat(a: ULong, b: ULong) = if (ULong.MAX_VALUE - a < b) ULong.MAX_VALUE else a + b
