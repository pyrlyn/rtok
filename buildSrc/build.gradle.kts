// Build logic shared by the modules (Kotlin/Native has no classpath resources, so text files are
// embedded as generated constants, like Rust's include_str!).
plugins { `kotlin-dsl` }
repositories { mavenCentral() }
