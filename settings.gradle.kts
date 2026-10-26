rootProject.name = "rtok-kotlin"
pluginManagement { repositories { gradlePluginPortal(); mavenCentral(); google() } }
// Remote build cache on Cloudflare R2 (docs/build-speed.md). Approved by Ivan 2026-10-26.
plugins { id("com.github.burrunan.s3-build-cache") version "1.9.10" }
dependencyResolutionManagement { repositories { mavenCentral(); google() } }
include(":humantime", ":toml", ":toml-ktoml", ":db", ":db-room", ":rtok-plugin-sdk", ":rtok-config", ":rtok-log", ":rtok-hook", ":rtok-store", ":rtok-sys", ":rtok-mcp", ":rtok-agent-sdk", ":rtok", ":rtok-wasm", ":rtok-e2e", ":http", ":http-ktor")
listOf("humantime", "toml", "toml-ktoml", "db", "db-room", "http", "http-ktor").forEach { project(":$it").projectDir = file("libs/$it") }

// The remote cache is on only when both R2 keys are in the environment; otherwise the build is local-cache only.
// Keys are read by the plugin from S3_BUILD_CACHE_ACCESS_KEY_ID / S3_BUILD_CACHE_SECRET_KEY, never from the repo.
fun cacheSetting(prop: String, env: String): String? =
    providers.gradleProperty("rtok.remoteCache.$prop").orNull ?: providers.environmentVariable(env).orNull
val r2Keys = listOf("S3_BUILD_CACHE_ACCESS_KEY_ID", "S3_BUILD_CACHE_SECRET_KEY").all { !providers.environmentVariable(it).orNull.isNullOrEmpty() }
if (r2Keys) buildCache {
    remote<com.github.burrunan.s3cache.AwsS3BuildCache> {
        region = "auto"
        isReducedRedundancy = false // R2 has no REDUCED_REDUNDANCY storage class
        bucket = requireNotNull(cacheSetting("bucket", "RTOK_REMOTE_CACHE_BUCKET")) { "set rtok.remoteCache.bucket or RTOK_REMOTE_CACHE_BUCKET" }
        prefix = cacheSetting("prefix", "RTOK_REMOTE_CACHE_PREFIX") ?: "rtok-kotlin/"
        endpoint = requireNotNull(cacheSetting("endpoint", "RTOK_REMOTE_CACHE_ENDPOINT")) { "set rtok.remoteCache.endpoint or RTOK_REMOTE_CACHE_ENDPOINT" }
        isPush = !providers.environmentVariable("CI").orNull.isNullOrEmpty()
    }
}
