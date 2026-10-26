// posix_spawn comes from the system <spawn.h>; see src/nativeInterop/cinterop/spawn.def.
// experiment/binary-split: the rtok_* C helpers are compiled once into librtok_spawn.a and linked via
// cinterop `staticLibraries` (docs/experiments/binary-split.md). macOS arm64 only, like the default target set.
val cDir = layout.projectDirectory.dir("src/nativeInterop/c")
val libDir = layout.buildDirectory.dir("native/macosArm64")
val compileSpawnC = tasks.register<Exec>("compileSpawnC") {
    inputs.dir(cDir); outputs.dir(libDir)
    val out = libDir.get().asFile
    doFirst { out.mkdirs() }
    commandLine("sh", "-c", "xcrun clang -c -O2 -arch arm64 -mmacosx-version-min=12.0 -I'${cDir.asFile}' '${cDir.file("rtok_spawn.c").asFile}' -o '$out/rtok_spawn.o' && xcrun ar rcs '$out/librtok_spawn.a' '$out/rtok_spawn.o'")
}
extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops.create("spawn") {
            includeDirs(cDir)
            extraOpts("-libraryPath", libDir.get().asFile.absolutePath)
        }
    }
}
tasks.matching { it.name.startsWith("cinteropSpawn") }.configureEach { dependsOn(compileSpawnC) }
