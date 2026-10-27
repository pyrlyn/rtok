// Adapter: the vendored zstd decoder (zstd.json pins the release tarball and its sha256) behind rtok.zstd, via
// cinterop over one static library compiled from lib/common + lib/decompress (no assembly, no threads).
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

val vendor = layout.buildDirectory.dir("vendor")
val libDir = layout.buildDirectory.dir("zstd")
val fetchZstd by tasks.registering(Exec::class) {
    description = "Downloads the pinned zstd release (zstd.json) into build/vendor and checks its sha256."
    inputs.file("zstd.json")
    outputs.dir(vendor)
    workingDir(projectDir)
    commandLine(
        "python3",
        "-c",
        """
import hashlib, io, json, os, tarfile, urllib.request
pin = json.load(open("zstd.json"))
data = urllib.request.urlopen(pin["url"]).read()
got = hashlib.sha256(data).hexdigest()
assert got == pin["sha256"], "zstd tarball sha256 " + got
out = "build/vendor"
with tarfile.open(fileobj=io.BytesIO(data)) as t:
    for m in t.getmembers():
        parts = m.name.split("/", 1)
        if len(parts) == 2 and parts[1].startswith("lib/") and m.isfile():
            m.name = parts[1]
            t.extract(m, out, filter="data")
        """.trimIndent(),
    )
}

/** Compiles librtok_zstd.a for one Kotlin/Native target with that target's C toolchain (buildSrc CrossCc). */
fun buildZstdFor(target: KotlinNativeTarget): TaskProvider<Exec> {
    val cap = target.name.replaceFirstChar(Char::uppercase)
    val out = libDir.get().dir(target.name)
    return tasks.register<Exec>("buildZstd$cap") {
        description = "Compiles librtok_zstd.a (zstd decoder + rtok_zstd.c) for ${target.name}."
        dependsOn(fetchZstd)
        inputs.dir(vendor)
        inputs.dir("src/nativeInterop/c")
        outputs.dir(out)
        workingDir(projectDir)
        val cc = CrossCc.forTarget(target.konanTarget.name)
        val flags = listOf("-DZSTD_DISABLE_ASM", "-DZSTD_MULTITHREAD=0", "-DZSTD_LEGACY_SUPPORT=0", "-Ibuild/vendor/lib", "-Ibuild/vendor/lib/common")
        val o = out.asFile.path
        commandLine(
            "sh",
            "-c",
            """
            set -e
            rm -rf '$o'; mkdir -p '$o'
            for f in build/vendor/lib/common/*.c build/vendor/lib/decompress/*.c src/nativeInterop/c/rtok_zstd.c; do
              ${cc.command(flags)} -c "${'$'}f" -o "$o/${'$'}(basename "${'$'}f" .c).o"
            done
            ${cc.archive("$o/librtok_zstd.a", "'$o'/*.o")}
            """.trimIndent(),
        )
    }
}
configure<KotlinMultiplatformExtension> {
    sourceSets.commonMain.dependencies { api(project(":zstd")) }
    targets.withType<KotlinNativeTarget>().configureEach {
        val target = name
        compilations.getByName("main").cinterops.create("zstd") {
            defFile(file("src/nativeInterop/cinterop/zstd.def"))
            includeDirs(file("src/nativeInterop/c"))
            extraOpts(
                "-libraryPath",
                libDir
                    .get()
                    .dir(target)
                    .asFile.absolutePath,
            )
        }
        // The static library is embedded in the cinterop klib, so it is an input of the cinterop task.
        val build = buildZstdFor(this)
        val lib = libDir.map { it.file("$target/librtok_zstd.a") }
        tasks.matching { it.name == "cinteropZstd" + target.replaceFirstChar(Char::uppercase) }.configureEach {
            dependsOn(build)
            inputs.file(lib)
        }
    }
}
