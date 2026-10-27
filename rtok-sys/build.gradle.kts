// posix_spawn comes from the system <spawn.h>; see src/nativeInterop/cinterop/spawn.def.
// The rtok_* C helpers are compiled into one librtok_spawn.a per Kotlin/Native target (build/native/<target>) with
// that target's C toolchain (buildSrc CrossCc: xcrun clang on macOS, Kotlin/Native's own clang + sysroot for Linux)
// and linked via cinterop `staticLibraries` (docs/experiments/binary-split.md).
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

val cDir = layout.projectDirectory.dir("src/nativeInterop/c")
configure<KotlinMultiplatformExtension> {
    targets.withType<KotlinNativeTarget>().configureEach {
        val target = this
        val cap = target.name.replaceFirstChar(Char::uppercase)
        val libDir = layout.buildDirectory.dir("native/${target.name}")
        val compile =
            tasks.register<Exec>("compileRtokSpawnC$cap") {
                description = "Compiles librtok_spawn.a for ${target.name}."
                inputs.dir(cDir)
                outputs.dir(libDir)
                val out = libDir.get().asFile
                val cc = CrossCc.forTarget(target.konanTarget.name)
                doFirst { out.mkdirs() }
                commandLine(
                    "sh",
                    "-c",
                    cc.compile(cDir.file("rtok_spawn.c").asFile.path, "$out/rtok_spawn.o", listOf("-I${cDir.asFile}")) +
                        " && " + cc.archive("$out/librtok_spawn.a", "'$out/rtok_spawn.o'"),
                )
            }
        compilations.getByName("main").cinterops.maybeCreate("spawn").apply {
            includeDirs(cDir)
            extraOpts("-libraryPath", libDir.get().asFile.absolutePath)
        }
        tasks.matching { it.name == "cinteropSpawn$cap" }.configureEach { dependsOn(compile) }
    }
}
