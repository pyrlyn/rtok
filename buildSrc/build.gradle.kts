// Build logic shared by the modules (Kotlin/Native has no classpath resources, so text files are
// embedded as generated constants, like Rust's include_str!).
plugins { `kotlin-dsl` }
repositories { mavenCentral() }
// Compresses the embedded binary assets (EmbedDir.kt); the runtime side decodes with the vendored C zstd.
dependencies { implementation("com.github.luben:zstd-jni:1.5.7-25") }
