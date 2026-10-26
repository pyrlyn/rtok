// Adapter: the tree-sitter C runtime and every vendored grammar (scripts/grammars.py, grammars.json)
// behind rtok.treesitter, via cinterop over one static library built with CMake (tools from mise.toml).
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

val vendor = layout.buildDirectory.dir("vendor")
val cmakeDir = layout.buildDirectory.dir("cmake")
val fetchGrammars by tasks.registering(Exec::class) {
    description = "Downloads the pinned tree-sitter runtime and grammars (grammars.json) into build/vendor."
    inputs.file("grammars.json"); inputs.file(rootDir.resolve("scripts/grammars.py"))
    outputs.dir(vendor)
    commandLine("python3", rootDir.resolve("scripts/grammars.py").absolutePath, "fetch")
}
val buildGrammars by tasks.registering(Exec::class) {
    description = "Builds librtok_ts.a (runtime + grammars + grammar table) with CMake/Ninja."
    dependsOn(fetchGrammars)
    inputs.dir(vendor); inputs.file("CMakeLists.txt"); inputs.dir("src/nativeInterop/c")
    outputs.dir(cmakeDir)
    workingDir(projectDir)
    commandLine("sh", "-c", "cmake -S . -B build/cmake -G Ninja -DCMAKE_BUILD_TYPE=Release >/dev/null && cmake --build build/cmake")
}
configure<KotlinMultiplatformExtension> {
    sourceSets.commonMain.dependencies { api(project(":treesitter")) }
    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops.create("ts") {
            defFile(file("src/nativeInterop/cinterop/ts.def"))
            includeDirs(vendor.get().dir("tree-sitter/lib/include").asFile, file("src/nativeInterop/c"))
            extraOpts("-libraryPath", cmakeDir.get().asFile.absolutePath)
        }
    }
}
// The static library is embedded in the cinterop klib, so it is an input of the cinterop task.
tasks.matching { it.name.startsWith("cinteropTs") }.configureEach { dependsOn(buildGrammars); inputs.file(cmakeDir.map { it.file("librtok_ts.a") }) }
