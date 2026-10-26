// The engine-neutral WASM interface the plugin host programs against (Rust uses wasmi directly).
// The only implementation lives in an adapter over the third-party interpreter, so swapping the
// engine never touches the host.
package rtok.wasm

/** A WASM value crossing the host boundary (the guest ABI uses only i32/i64). */
sealed interface WasmValue {
    data class I32(val v: Int) : WasmValue
    data class I64(val v: Long) : WasmValue
}

/** Raised for a module that does not validate, a trap, or fuel/step exhaustion. */
class WasmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Guest linear memory, as the host sees it. */
interface WasmMemory {
    fun read(offset: Int, length: Int): ByteArray
    fun write(offset: Int, bytes: ByteArray)
}

/** A host function the guest imports: `module.name`, given the guest memory and its arguments. */
class HostFunction(
    val module: String, val name: String,
    val params: List<Class<*>>, val results: List<Class<*>>,
    val call: (memory: WasmMemory, args: List<WasmValue>) -> List<WasmValue>,
) {
    /** Value type markers of [params] / [results]. */
    sealed interface Class<T : WasmValue> { data object I32 : Class<WasmValue.I32>; data object I64 : Class<WasmValue.I64> }
}

/** An instantiated module. */
interface WasmInstance {
    val memory: WasmMemory
    fun call(export: String, args: List<WasmValue>): List<WasmValue>
    fun hasExport(name: String): Boolean
}

/** The engine: validate and instantiate a module against host imports. */
interface WasmRuntime {
    fun instantiate(module: ByteArray, imports: List<HostFunction>): WasmInstance
}
