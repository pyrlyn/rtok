// Port of src/hooks/push.rs (T288): unread agent messages pushed into a prompt or tool result,
// framed, within `[agents] push_bytes`, and marked delivered only once they reached the context.
package rtok.hooks

import rtok.agentMessageEnd
import rtok.agentMessageFrame
import rtok.plugin.Runtime
import rtok.plugin.sdk.Injection
import rtok.store.Message
import rtok.store.markDelivered
import rtok.store.undelivered

internal class Push(val text: String, private val framed: List<Int>, private val oversized: List<Int>, private val more: String?) {
    fun injection() = Injection("agent_message", text, 10u)

    /** Marks delivered the frames whose closing line is in [out], and the oversized ones once the more-line made it. */
    fun settle(cx: Runtime, out: String) {
        val lines = out.lines()
        val ids = framed.filter { agentMessageEnd(it) in lines }.toMutableList()
        if (more != null && more in out) ids += oversized
        runCatching { cx.store.markDelivered(ids) }
    }

    companion object {
        fun pending(cx: Runtime, agent: String?): Push? {
            val rows = runCatching { cx.store.undelivered(agent ?: return null) }.getOrNull() ?: return null
            return if (rows.isEmpty()) null else fit(rows, cx.config.agents.pushBytes.toInt())
        }

        fun moreLine(n: Int) = "… and $n more: call agent_inbox (or run rtok agents inbox)"

        /** Byte lengths, like Rust `String::len`. */
        private fun String.utf8Len() = encodeToByteArray().size

        fun fit(rows: List<Message>, cap: Int): Push {
            val frames = rows.map(::agentMessageFrame)
            val room = if (frames.sumOf { it.utf8Len() } <= cap) cap else maxOf(0, cap - (moreLine(rows.size).utf8Len() + 1))
            val text = StringBuilder(); val framed = mutableListOf<Int>(); val oversized = mutableListOf<Int>()
            var full = false
            for ((m, frame) in rows.zip(frames)) {
                when {
                    frame.utf8Len() > room -> oversized += m.id
                    !full && text.toString().utf8Len() + frame.utf8Len() <= room -> { text.append(frame); framed += m.id }
                    else -> full = true
                }
            }
            val rest = rows.size - framed.size
            var more: String? = null
            if (rest > 0) { more = moreLine(rest); text.append(more) }
            return Push(text.toString().trimEnd(), framed, oversized, more)
        }
    }
}
