// The Chasm adapter: the only file that sees Chasm types. Everything else uses rtok.wasm.
package rtok.wasm.chasm

import io.github.charlietap.chasm.config.StoreConfig
import io.github.charlietap.chasm.embedding.addFuel
import io.github.charlietap.chasm.embedding.dsl.imports
import io.github.charlietap.chasm.embedding.error.ChasmError
import io.github.charlietap.chasm.embedding.exports
import io.github.charlietap.chasm.embedding.instance
import io.github.charlietap.chasm.embedding.invoke
import io.github.charlietap.chasm.embedding.memory.readBytes
import io.github.charlietap.chasm.embedding.memory.sizeMemory
import io.github.charlietap.chasm.embedding.memory.writeBytes
import io.github.charlietap.chasm.embedding.module
import io.github.charlietap.chasm.embedding.resetFuel
import io.github.charlietap.chasm.embedding.shapes.ChasmResult
import io.github.charlietap.chasm.embedding.shapes.Instance
import io.github.charlietap.chasm.embedding.shapes.Memory
import io.github.charlietap.chasm.embedding.shapes.Store
import io.github.charlietap.chasm.embedding.store
import io.github.charlietap.chasm.embedding.validate
import io.github.charlietap.chasm.host.readI32
import io.github.charlietap.chasm.host.readI64
import io.github.charlietap.chasm.host.writeI32
import io.github.charlietap.chasm.host.writeI64
import io.github.charlietap.chasm.runtime.value.ExecutionValue
import io.github.charlietap.chasm.runtime.value.NumberValue
import rtok.wasm.HostFunction
import rtok.wasm.WasmException
import rtok.wasm.WasmInstance
import rtok.wasm.WasmMemory
import rtok.wasm.WasmRuntime
import rtok.wasm.WasmType
import rtok.wasm.WasmValue

/** [WasmRuntime] over Chasm's interpreter. Modules are validated before they are instantiated. */
object ChasmRuntime : WasmRuntime {
    override fun instantiate(
        module: ByteArray,
        imports: List<HostFunction>,
        fuel: Long?,
    ): WasmInstance {
        val decoded = module(module).orThrow("decode")
        val valid = validate(decoded).orThrow("validate")
        val store = store(StoreConfig(meterFuel = fuel != null))
        val handle = ChasmInstance(store, fuel != null)
        if (fuel != null) handle.setFuel(fuel)
        val linked =
            imports(store) {
                for (f in imports) {
                    function {
                        moduleName = f.module
                        entityName = f.name
                        type {
                            params { f.params.forEach { if (it == WasmType.I32) i32() else i64() } }
                            results { f.results.forEach { if (it == WasmType.I32) i32() else i64() } }
                        }
                        reference { parameters, results ->
                            val args = f.params.mapIndexed { i, t -> if (t == WasmType.I32) WasmValue.I32(parameters.readI32(i)) else WasmValue.I64(parameters.readI64(i)) }
                            f.call(handle.memory, args).forEachIndexed { i, v ->
                                when (v) {
                                    is WasmValue.I32 -> results.writeI32(i, v.v)
                                    is WasmValue.I64 -> results.writeI64(i, v.v)
                                }
                            }
                        }
                    }
                }
            }
        handle.bind(instance(store, valid, linked).orThrow("instantiate"))
        return handle
    }
}

private class ChasmInstance(
    private val store: Store,
    private val metered: Boolean,
) : WasmInstance {
    private var instance: Instance? = null
    override var memory: WasmMemory? = null
        private set

    fun bind(instance: Instance) {
        this.instance = instance
        memory =
            exports(instance)
                .firstOrNull { it.name == "memory" }
                ?.value
                ?.let { it as? Memory }
                ?.let { ChasmMemory(store, it) }
    }

    override fun call(
        export: String,
        args: List<WasmValue>,
    ): List<WasmValue> {
        val inst = instance ?: throw WasmException("instance not ready")
        val values: List<ExecutionValue> =
            args.map {
                when (it) {
                    is WasmValue.I32 -> NumberValue.I32(it.v)
                    is WasmValue.I64 -> NumberValue.I64(it.v)
                }
            }
        return invoke(store, inst, export, values).orThrow(export).map {
            when (it) {
                is NumberValue.I32 -> WasmValue.I32(it.value)
                is NumberValue.I64 -> WasmValue.I64(it.value)
                else -> throw WasmException("$export: unsupported result type")
            }
        }
    }

    override fun hasExport(name: String) = instance?.let { i -> exports(i).any { it.name == name } } ?: false

    override fun setFuel(fuel: Long) {
        if (!metered) return
        resetFuel(store).orThrow("fuel")
        addFuel(store, fuel).orThrow("fuel")
    }
}

private class ChasmMemory(
    private val store: Store,
    private val memory: Memory,
) : WasmMemory {
    override val size: Int get() = sizeMemory(store, memory).orThrow("memory size")

    override fun read(
        offset: Int,
        length: Int,
    ): ByteArray {
        if (offset < 0 || length < 0 || offset.toLong() + length > size) throw WasmException("guest memory read out of bounds")
        return readBytes(store, memory, ByteArray(length), offset, length).orThrow("memory read")
    }

    override fun write(
        offset: Int,
        bytes: ByteArray,
    ) {
        if (offset < 0 || offset.toLong() + bytes.size > size) throw WasmException("guest memory write out of bounds")
        writeBytes(store, memory, offset, bytes).orThrow("memory write")
    }
}

private fun <S, E : ChasmError> ChasmResult<S, E>.orThrow(what: String): S =
    when (this) {
        is ChasmResult.Success -> result
        is ChasmResult.Error -> throw WasmException("$what: $error")
    }
