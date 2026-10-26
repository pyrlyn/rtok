// Port of crates/rtok-store/src/task_id.rs: `R12`, `R12.3`, `AT1.2.3` (prefix + 1..3 numbers from 1).
package rtok.store

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import rtok.plugin.sdk.TASK_PREFIX_MAX
import rtok.plugin.sdk.checkTaskPrefix
import rtok.plugin.sdk.debugStr

const val MAX_DEPTH = 3
const val MAX_PREFIX = TASK_PREFIX_MAX

fun checkPrefix(prefix: String) = checkTaskPrefix(prefix)

@Serializable(with = TaskId.Ser::class)
class TaskId private constructor(
    val prefix: String,
    val path: List<UInt>,
) : Comparable<TaskId> {
    companion object {
        fun new(
            prefix: String,
            n: UInt,
        ) = fromParts(prefix, listOf(n))

        private fun fromParts(
            prefix: String,
            path: List<UInt>,
        ): TaskId {
            checkPrefix(prefix)
            require(path.isNotEmpty() && path.size <= MAX_DEPTH) { "task id needs 1 to $MAX_DEPTH numbers, got ${path.size}" }
            require(0u !in path) { "task numbers start at 1" }
            return TaskId(prefix.uppercase(), path)
        }

        fun parse(raw: String): TaskId {
            val s = raw.trim()
            val digits = s.indexOfFirst { !(it in 'a'..'z' || it in 'A'..'Z') }.let { if (it < 0) s.length else it }
            val prefix = s.substring(0, digits)
            val rest = s.substring(digits)
            require(rest.isNotEmpty()) { "task id ${debugStr(s)} has no number" }
            val path =
                rest.split('.').map { part ->
                    require(part.isNotEmpty() && !part.startsWith('0') && part.all { it in '0'..'9' }) { "task id ${debugStr(s)}: ${debugStr(part)} is not a number from 1" }
                    part.toUIntOrNull() ?: throw IllegalArgumentException("task id ${debugStr(s)}: number too large to fit in target type")
                }
            return try {
                fromParts(prefix, path)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("task id ${debugStr(s)}: ${e.message}")
            }
        }
    }

    fun child(n: UInt) = fromParts(prefix, path + n)

    fun parent(): TaskId? = if (path.size > 1) TaskId(prefix, path.dropLast(1)) else null

    override fun toString() = prefix + path.joinToString(".")

    override fun equals(other: Any?) = other is TaskId && other.prefix == prefix && other.path == path

    override fun hashCode() = prefix.hashCode() * 31 + path.hashCode()

    override fun compareTo(other: TaskId): Int {
        prefix.compareTo(other.prefix).let { if (it != 0) return it }
        for (i in 0 until minOf(path.size, other.path.size)) path[i].compareTo(other.path[i]).let { if (it != 0) return it }
        return path.size.compareTo(other.path.size)
    }

    object Ser : KSerializer<TaskId> {
        override val descriptor = PrimitiveSerialDescriptor("TaskId", PrimitiveKind.STRING)

        override fun serialize(
            encoder: Encoder,
            value: TaskId,
        ) = encoder.encodeString(value.toString())

        override fun deserialize(decoder: Decoder) = parse(decoder.decodeString())
    }
}
