// Port of the path rule of src/measure/subagents.rs that `rtok agents usage` needs: a Claude Code sub-agent
// transcript lives in `<session>/subagents/`.
package rtok.measure

import rtok.sys.fileName
import rtok.sys.parent

/** `is_subagent`: the transcript's parent directory is named `subagents`. */
internal fun isSubagent(path: String): Boolean = parent(path)?.let(::fileName) == "subagents"
