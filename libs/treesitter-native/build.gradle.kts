// Adapter: the tree-sitter C runtime and every vendored grammar (scripts/grammars.py, grammars.json)
// behind rtok.treesitter, via cinterop over one static library built with CMake (tools from mise.toml).
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

val vendor = layout.buildDirectory.dir("vendor")
val cmakeDir = layout.buildDirectory.dir("cmake")
// Dev-only C coverage (scripts/coverage.py sets RTOK_TS_COVERAGE=1); off for every normal and release build.
val cCoverage = providers.environmentVariable("RTOK_TS_COVERAGE").orNull == "1"
val fetchGrammars by tasks.registering(Exec::class) {
    description = "Downloads the pinned tree-sitter runtime and grammars (grammars.json) into build/vendor."
    inputs.file("grammars.json")
    inputs.file(rootDir.resolve("scripts/grammars.py"))
    outputs.dir(vendor)
    commandLine("python3", rootDir.resolve("scripts/grammars.py").absolutePath, "fetch")
}

/** Builds librtok_ts.a (runtime + grammars + grammar table) for one Kotlin/Native target with CMake/Ninja, using that target's C toolchain (buildSrc CrossCc). */
fun buildGrammarsFor(target: KotlinNativeTarget): TaskProvider<Exec> {
    val cap = target.name.replaceFirstChar(Char::uppercase)
    val out = cmakeDir.get().dir(target.name).asFile
    val cc = CrossCc.forTarget(target.konanTarget.name)
    val cmake = listOf("cmake", "-S", ".", "-B", "'${out.path}'", "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release", "-DRTOK_TS_COVERAGE=${if (cCoverage) "ON" else "OFF"}") + cc.cmakeArgs()
    return tasks.register<Exec>("buildGrammars$cap") {
        description = "Builds librtok_ts.a (runtime + grammars + grammar table) for ${target.name} with CMake/Ninja."
        dependsOn(fetchGrammars)
        inputs.dir(vendor)
        inputs.file("CMakeLists.txt")
        inputs.dir("src/nativeInterop/c")
        outputs.dir(out)
        workingDir(projectDir)
        inputs.property("coverage", cCoverage)
        commandLine("sh", "-c", cmake.joinToString(" ") + " >/dev/null && cmake --build '${out.path}'")
    }
}
configure<KotlinMultiplatformExtension> {
    sourceSets.commonMain.dependencies { api(project(":treesitter")) }
    targets.withType<KotlinNativeTarget>().configureEach {
        val target = name
        val lib = cmakeDir.map { it.file("$target/librtok_ts.a") }
        compilations.getByName("main").cinterops.create("ts") {
            defFile(file("src/nativeInterop/cinterop/ts.def"))
            includeDirs(vendor.get().dir("tree-sitter/lib/include").asFile, file("src/nativeInterop/c"))
            extraOpts(
                "-libraryPath",
                cmakeDir
                    .get()
                    .dir(target)
                    .asFile.absolutePath,
            )
        }
        if (cCoverage) binaries.getTest("DEBUG").linkerOpts(clangProfileRuntime())
        // The static library is embedded in the cinterop klib, so it is an input of the cinterop task.
        val build = buildGrammarsFor(this)
        tasks.matching { it.name == "cinteropTs" + target.replaceFirstChar(Char::uppercase) }.configureEach {
            dependsOn(build)
            inputs.file(lib)
        }
    }
}

/** clang's profile runtime, needed only when the runtime is instrumented. */
fun clangProfileRuntime(): String {
    val dir =
        providers
            .exec { commandLine("xcrun", "clang", "-print-resource-dir") }
            .standardOutput.asText
            .get()
            .trim()
    return "$dir/lib/darwin/libclang_rt.profile_osx.a"
}
if (cCoverage) {
    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
        environment(
            "LLVM_PROFILE_FILE",
            layout.buildDirectory
                .file("coverage/ts-%p.profraw")
                .get()
                .asFile.absolutePath,
        )
    }
}
