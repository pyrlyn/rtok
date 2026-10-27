// Port of src/worktree/mod.rs `noted_table`: a `render::table` with one free-text note per line.
package rtok.worktree

import rtok.Col
import rtok.table

/** [table] with one note appended per line (after a `note` header), so the table itself still ends right-aligned. */
fun notedTable(
    cols: List<Col>,
    lines: List<List<String>>,
    notes: List<String>,
): String {
    val all = listOf("note") + notes
    return table(cols, lines)
        .removeSuffix("\n")
        .split('\n')
        .zip(all)
        .joinToString("") { (row, note) -> "$row  $note\n" }
}
