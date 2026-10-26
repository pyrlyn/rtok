// The engine-neutral WASM interface the plugin host programs against (Rust uses wasmi directly).
// The only implementation lives in an adapter over the third-party interpreter (:wasm-chasm), so
// swapping the engine never touches the host.
package rtok.wasm

/** A WASM value crossing the host boundary (the guest ABI uses only i32/i64). */
sealed interface WasmValue {
    data class I32(val v: Int) : WasmValue
    data class I64(val v: Long) : WasmValue
}

/** WASM value types of host-function signatures. */
enum class WasmType { I32, I64 }

/** Raised for a module that does not decode or validate, a trap, a missing export, or exhausted fuel. */
class WasmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Guest linear memory, as the host sees it. Reads and writes outside it throw [WasmException]. */
interface WasmMemory {
    val size: Int
    fun read(offset: Int, length: Int): ByteArray
    fun write(offset: Int, bytes: ByteArray)
}

/**
 * A host function the guest imports as `module.name`. [call] gets the guest's exported `memory`
 * (null when it exports none) and its arguments; throwing traps the guest call.
 */
class HostFunction(
    val module: String, val name: String,
    val params: List<WasmType>, val results: List<WasmType>,
    val call: (memory: WasmMemory?, args: List<WasmValue>) -> List<WasmValue>,
)

/** An instantiated module. */
interface WasmInstance {
    /** The exported `memory`, if any. */
    val memory: WasmMemory?
    fun call(export: String, args: List<WasmValue> = emptyList()): List<WasmValue>
    fun hasExport(name: String): Boolean
    /** Sets the fuel left for the next calls (metered instances only; about one unit per call or loop turn). */
    fun setFuel(fuel: Long)
}

/** The engine: decode, validate and instantiate a module against host imports. */
interface WasmRuntime {
    /**
     * Instantiates [module]. With [fuel] set, every call (the start function included) is metered
     * and traps once the fuel runs out; null runs unmetered.
     */
    fun instantiate(module: ByteArray, imports: List<HostFunction>, fuel: Long? = null): WasmInstance
}

/** Bounds-checked copy of `[offset, offset + length)` out of [data]; both come from an untrusted guest. */
fun guestSlice(data: ByteArray, offset: Int, length: Int): ByteArray {
    if (offset < 0 || length < 0 || offset.toLong() + length > data.size) throw WasmException("guest memory read out of bounds")
    return data.copyOfRange(offset, offset + length)
}
