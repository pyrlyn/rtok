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
val buildZstd by tasks.registering(Exec::class) {
    description = "Compiles librtok_zstd.a (zstd decoder + rtok_zstd.c)."
    dependsOn(fetchZstd)
    inputs.dir(vendor)
    inputs.dir("src/nativeInterop/c")
    outputs.dir(libDir)
    workingDir(projectDir)
    commandLine(
        "sh",
        "-c",
        """
        set -e
        out=build/zstd; rm -rf ${'$'}out; mkdir -p ${'$'}out
        for f in build/vendor/lib/common/*.c build/vendor/lib/decompress/*.c src/nativeInterop/c/rtok_zstd.c; do
          cc -O2 -fPIC -DZSTD_DISABLE_ASM -DZSTD_MULTITHREAD=0 -DZSTD_LEGACY_SUPPORT=0 -Ibuild/vendor/lib -Ibuild/vendor/lib/common \
            -c "${'$'}f" -o "${'$'}out/${'$'}(basename "${'$'}f" .c).o"
        done
        ar rcs ${'$'}out/librtok_zstd.a ${'$'}out/*.o
        """.trimIndent(),
    )
}
configure<KotlinMultiplatformExtension> {
    sourceSets.commonMain.dependencies { api(project(":zstd")) }
    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops.create("zstd") {
            defFile(file("src/nativeInterop/cinterop/zstd.def"))
            includeDirs(file("src/nativeInterop/c"))
            extraOpts("-libraryPath", libDir.get().asFile.absolutePath)
        }
    }
}
// The static library is embedded in the cinterop klib, so it is an input of the cinterop task.
tasks.matching { it.name.startsWith("cinteropZstd") }.configureEach {
    dependsOn(buildZstd)
    inputs.file(libDir.map { it.file("librtok_zstd.a") })
}
