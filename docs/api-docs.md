# API docs (Dokka)

## How it works
The JetBrains Dokka 2 Gradle plugin (`org.jetbrains.dokka` 2.2.0) is declared once in the root
`build.gradle.kts`: the `subprojects {}` convention applies it to every module, and the root
project aggregates all of them (`dependencies { subprojects.forEach { add("dokka", it) } }`) into
one HTML site. KDoc comments in the sources are the content.

## Enable / disable
On demand only: `./gradlew dokkaGenerate` → `build/dokka/html/index.html`.
No `build`, `assemble`, `check` or test task depends on Dokka, so debug builds and tests never run it.
To remove it, delete the plugin line, the `dependencies { … "dokka" … }` line and the `apply(plugin = "org.jetbrains.dokka")` line.

## Test
`./gradlew dokkaGenerate` must succeed and write `build/dokka/html/index.html` with one entry per module.
Checked on the Mac: first run 2 min 9 s (38 tasks, configuration cache stored); an incremental
`:rtok-store:macosArm64Test` after the change ran no Dokka task and reused the configuration cache
(26–28 s, same as without the plugin).

## Limitations
- Kotlin/Native only (macosArm64 source sets); `linuxX64`/`linuxArm64` show up only with `-Prtok.allTargets=true`.
- No links to external API docs or source links yet.
