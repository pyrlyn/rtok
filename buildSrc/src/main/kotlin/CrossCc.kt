import java.io.File

/**
 * A C compiler and archiver for one Kotlin/Native target, so a module's own C code links into that target.
 *
 * - macOS targets use `xcrun clang` and `xcrun ar` with the arch and minimum OS.
 * - Linux targets use the clang that Kotlin/Native downloads itself (`~/.konan/dependencies/llvm-*`), with the
 *   gcc toolchain and sysroot of that target. These are the same flags `run_konan clang clang <target>` passes, so
 *   nothing beyond Kotlin/Native's own toolchain is needed.
 *
 * [cc] and [ar] are shell words. [cFlags] go after the compiler; CMake gets them via `CMAKE_C_FLAGS`.
 */
class CrossCc private constructor(
    val cc: String,
    val ar: String,
    val cFlags: List<String>,
    /** CMake `CMAKE_SYSTEM_NAME` for a cross build, or null on the host. */
    val cmakeSystem: String?,
) {
    /** `-c src -o obj` for one file, as one shell command. */
    fun compile(
        src: String,
        obj: String,
        extra: List<String> = emptyList(),
    ): String = command(extra) + " -c " + q(src) + " -o " + q(obj)

    /** The compiler with its flags and [extra], for a shell loop that appends `-c "${'$'}f" -o …` itself. */
    fun command(extra: List<String> = emptyList()): String = (listOf(cc) + cFlags.map(::q) + extra.map(::q)).joinToString(" ")

    /** `ar rcs lib objs…`, as one shell command. */
    fun archive(
        lib: String,
        objsGlob: String,
    ): String = "$ar rcs ${q(lib)} $objsGlob"

    /** CMake configure flags that point the build at this compiler. */
    fun cmakeArgs(): List<String> =
        if (cmakeSystem == null) {
            emptyList()
        } else {
            listOf(
                "-DCMAKE_SYSTEM_NAME=$cmakeSystem",
                "-DCMAKE_C_COMPILER=$cc",
                "-DCMAKE_AR=$ar",
                // llvm-ar already writes the symbol index; the host (Apple) ranlib would empty an ELF archive.
                "-DCMAKE_RANLIB=/usr/bin/true",
                "-DCMAKE_C_FLAGS=${q(cFlags.joinToString(" "))}",
                // The sysroot is complete for compiling; a try-compile link check would need the target's libc.
                "-DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY",
            )
        }

    companion object {
        private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

        /** The compiler for the Kotlin/Native target named [konanTarget] (`macos_arm64`, `linux_x64`, `linux_arm64`). */
        fun forTarget(
            konanTarget: String,
            minMacos: String = "12.0",
            konanData: File = File(System.getenv("KONAN_DATA_DIR") ?: (System.getProperty("user.home") + "/.konan")),
        ): CrossCc =
            when (konanTarget) {
                "macos_arm64" -> CrossCc("xcrun clang", "xcrun ar", listOf("-O2", "-arch", "arm64", "-mmacosx-version-min=$minMacos"), null)
                "macos_x64" -> CrossCc("xcrun clang", "xcrun ar", listOf("-O2", "-arch", "x86_64", "-mmacosx-version-min=$minMacos"), null)
                "linux_x64" -> linux(konanData, "x86_64-unknown-linux-gnu")
                "linux_arm64" -> linux(konanData, "aarch64-unknown-linux-gnu")
                else -> error("rtok build: no C toolchain for $konanTarget")
            }

        private fun linux(
            konanData: File,
            triple: String,
        ): CrossCc {
            val deps = konanData.resolve("dependencies")
            val llvm =
                newest(deps) { it.name.startsWith("llvm-") && it.resolve("bin/clang").isFile }
                    ?: error("rtok build: no Kotlin/Native llvm under $deps (build a Kotlin/Native target once to download it)")
            val gcc =
                newest(deps) { it.name.startsWith("$triple-gcc-") }
                    ?: error("rtok build: no Kotlin/Native $triple toolchain under $deps (build the target once to download it)")
            val bin = llvm.resolve("bin")
            val flags =
                listOf(
                    "-O2",
                    "-fPIC",
                    "-fno-stack-protector",
                    "-B$bin",
                    "--gcc-toolchain=$gcc",
                    "-target",
                    triple,
                    "--sysroot=${gcc.resolve("$triple/sysroot")}",
                )
            return CrossCc(q(bin.resolve("clang").path), q(bin.resolve("llvm-ar").path), flags, "Linux")
        }

        /** The last match by name (versions in the directory names sort with them). */
        private fun newest(
            dir: File,
            pick: (File) -> Boolean,
        ): File? = dir.listFiles()?.filter { it.isDirectory && pick(it) }?.maxByOrNull { it.name }
    }
}
