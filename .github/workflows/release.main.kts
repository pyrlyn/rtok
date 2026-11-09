#!/usr/bin/env kotlin
@file:Repository("https://repo.maven.apache.org/maven2/")
@file:DependsOn("io.github.typesafegithub:github-workflows-kt:4.0.0")

import io.github.typesafegithub.workflows.domain.Concurrency
import io.github.typesafegithub.workflows.domain.Environment
import io.github.typesafegithub.workflows.domain.Mode
import io.github.typesafegithub.workflows.domain.Permission
import io.github.typesafegithub.workflows.domain.RunnerType
import io.github.typesafegithub.workflows.domain.RunnerType.UbuntuLatest
import io.github.typesafegithub.workflows.domain.actions.CustomAction
import io.github.typesafegithub.workflows.domain.triggers.WorkflowDispatch
import io.github.typesafegithub.workflows.domain.triggers.WorkflowDispatch.Input
import io.github.typesafegithub.workflows.dsl.JobBuilder
import io.github.typesafegithub.workflows.dsl.workflow
import io.github.typesafegithub.workflows.yaml.ConsistencyCheckJobConfig
import io.github.typesafegithub.workflows.yaml.Preamble

// Prerelease of rtok, started by hand (workflow_dispatch). Structure follows pyrlyn/ci's
// reusable release.yml (checks -> verify -> build -> release -> notify-failure), adapted to the
// Kotlin/Gradle build. Stages:
//
//   checks  -> compute the next prerelease version from Version.kt, the `bump` input and the
//              existing tags (X.Y.Z-alpha.N), write Version.kt and CHANGELOG.md, hand them on
//   verify  -> full test suite on macOS arm64 and Linux x64 (same commands as CI, #253)
//   build   -> release binaries per target, archives + sha256
//   release -> only if everything above is green, and only after a reviewer approves the
//              `release` environment: commits the bump on a release/<tag> branch, pushes
//              the tag and creates a DRAFT prerelease with the archives, opens a PR for the bump
//
// A red verify or build stops the run before the `release` job; nothing is tagged or published.
// `dry-run: true` (default) stops after build.
//
// Actions are pinned to commit SHAs (the tag is in the comment next to each), so they are
// CustomAction rather than typed bindings, which only emit tags.

fun pinned(owner: String, name: String, sha: String, with: Map<String, String> = emptyMap()) =
    CustomAction(actionOwner = owner, actionName = name, actionVersion = sha, inputs = with)

val checkoutSha = "3d3c42e5aac5ba805825da76410c181273ba90b1" // v7.0.1
fun checkout(with: Map<String, String> = emptyMap()) = pinned("actions", "checkout", checkoutSha, with)
fun uploadArtifact(with: Map<String, String>) =
    pinned("actions", "upload-artifact", "cf430e030ddbb5b0abf93d22962f4752f3646cd9", with) // v7.0.2
fun downloadArtifact(with: Map<String, String>) =
    pinned("actions", "download-artifact", "9000827ccba6bdab643e8b6fd33ac0654aef8333", with) // v8.0.2

val bumpArtifact = mapOf("name" to "bump", "path" to ".")
val konanBootstrap =
    "KOTLIN_VERSION=$(mise current kotlin) ./gradlew -p scripts/ci/konan-bootstrap linkDebugExecutableLinuxX64 linkDebugExecutableLinuxArm64 --console=plain"
val bashDefaults = mapOf("defaults" to mapOf("run" to mapOf("shell" to "bash")))

// Shared by verify and build: sources, shared builds, the bump, toolchains and the konan cache.
fun JobBuilder<*>.setUp() {
    uses(action = checkout(mapOf("persist-credentials" to "false")))
    uses(
        action =
            checkout(
                mapOf("repository" to "pyrlyn/packages", "ref" to "0.0.1", "path" to "packages", "persist-credentials" to "false"),
            ),
    )
    uses(action = downloadArtifact(bumpArtifact))
    uses(action = pinned("jdx", "mise-action", "6be3c7ed4c21d4f76b208d351d95aac273ebb76d")) // v5.2.4
    uses(action = pinned("gradle", "actions/setup-gradle", "8da46b539cd8fe6ab27ca75128919a18d757604c", mapOf("cache-read-only" to "true"))) // v6.4.1
    uses(
        action =
            pinned(
                "actions", "cache", "55cc8345863c7cc4c66a329aec7e433d2d1c52a9", // v6.1.0
                mapOf(
                    "path" to "~/.konan",
                    "key" to "konan-\${{ runner.os }}-\${{ hashFiles('mise.toml', 'scripts/ci/konan-bootstrap/**') }}",
                    "restore-keys" to "konan-\${{ runner.os }}-",
                ),
            ),
    )
    run(name = "Prepare the Kotlin/Native toolchain", condition = "matrix.prepare", command = "\${{ matrix.prepare }}")
}

workflow(
    name = "release",
    on =
        listOf(
            WorkflowDispatch(
                inputs =
                    mapOf(
                        "bump" to Input(
                            description = "Part of the version to bump for the next prerelease cycle.",
                            required = false,
                            type = Input.Type.Choice,
                            options = listOf("major", "minor", "patch"),
                            default = "minor",
                        ),
                        "pre-id" to Input(
                            description = "Prerelease identifier (X.Y.Z-<pre-id>.N).",
                            required = false,
                            type = Input.Type.String,
                            default = "alpha",
                        ),
                        "dry-run" to Input(
                            description = "Run checks, verify and build only; never tag or create a release.",
                            required = false,
                            type = Input.Type.Boolean,
                            default = "true",
                        ),
                    ),
            ),
        ),
    permissions = mapOf(Permission.Contents to Mode.Read),
    concurrency = Concurrency(group = "release", cancelInProgress = false),
    env =
        mapOf(
            "SHARED_SETTINGS_DIR" to "\${{ github.workspace }}/packages/shared-settings",
            "KOTLIN_WASM_DIR" to "\${{ github.workspace }}/packages/kotlin-wasm",
            "KOTLIN_SNAPSHOT_DIR" to "\${{ github.workspace }}/packages/kotlin-snapshot",
            "KOTLIN_WEBGL_DIR" to "\${{ github.workspace }}/packages/kotlin-webgl",
            "KOTLIN_KIT_DIR" to "\${{ github.workspace }}/packages/kotlin-kit",
            "VERSION_FILE" to "rtok/src/commonMain/kotlin/rtok/Version.kt",
        ),
    sourceFile = __FILE__,
    targetFileName = "release.yml",
    preamble = Preamble.WithOriginalAfter("DO NOT EDIT: generated from release.main.kts; edit that file and run it to regenerate."),
    // A consistency job here would only run on a release; ci.main.kts checks release.yml on every push and PR instead.
    consistencyCheckJobConfig = ConsistencyCheckJobConfig.Disabled,
) {
    val checks =
        job(
            id = "checks",
            runsOn = UbuntuLatest,
            timeoutMinutes = 15,
            _customArguments =
                mapOf(
                    "outputs" to
                        mapOf(
                            "version" to "\${{ steps.version.outputs.version }}",
                            "tag" to "\${{ steps.version.outputs.tag }}",
                        ),
                ),
        ) {
            uses(action = checkout(mapOf("fetch-depth" to "0", "persist-credentials" to "false")))
            run(
                name = "Ref",
                command = $$"""[ "$GITHUB_REF" = "refs/heads/main" ] || { echo "::error title=release::run from main, not $GITHUB_REF"; exit 1; }""" + "\n",
            )
            run(
                id = "version",
                name = "Next version",
                env = mapOf("BUMP" to "\${{ inputs.bump }}", "PRE" to "\${{ inputs.pre-id }}"),
                command =
                    $$"""
                    set -euo pipefail
                    [[ "$PRE" =~ ^[a-z]+$ ]] || { echo "::error::invalid pre-id '$PRE'"; exit 1; }
                    current="$(sed -nE 's/^const val VERSION = "([^"]+)"$/\1/p' "$VERSION_FILE")"
                    [[ "$current" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)(-.*)?$ ]] || { echo "::error::cannot parse VERSION '$current'"; exit 1; }
                    ma="${BASH_REMATCH[1]}"; mi="${BASH_REMATCH[2]}"; pa="${BASH_REMATCH[3]}"
                    # If Version.kt's own prerelease is already tagged, its cycle is open: keep the core and
                    # count up N. Otherwise start a new cycle with the bump.
                    if [ -n "${BASH_REMATCH[4]}" ] && git rev-parse -q --verify "refs/tags/v$current" >/dev/null; then
                      core="$ma.$mi.$pa"
                    else
                      case "$BUMP" in
                        major) core="$((ma + 1)).0.0" ;;
                        minor) core="$ma.$((mi + 1)).0" ;;
                        patch) [ -n "${BASH_REMATCH[4]}" ] && core="$ma.$mi.$pa" || core="$ma.$mi.$((pa + 1))" ;;
                      esac
                    fi
                    n="$(git tag -l "v$core-$PRE.*" | sed -nE "s/^v$core-$PRE\.([0-9]+)$/\1/p" | sort -n | tail -n1)"
                    version="$core-$PRE.$(( ${n:-0} + 1 ))"
                    if git rev-parse -q --verify "refs/tags/v$version" >/dev/null; then
                      echo "::error::tag v$version already exists"; exit 1
                    fi
                    echo "version=$version" >> "$GITHUB_OUTPUT"
                    echo "tag=v$version" >> "$GITHUB_OUTPUT"
                    echo "### $current -> $version (bump: $BUMP)" >> "$GITHUB_STEP_SUMMARY"
                    """.trimIndent() + "\n",
            )
            run(
                name = "Version.kt and CHANGELOG.md",
                env = mapOf("VERSION" to "\${{ steps.version.outputs.version }}"),
                command =
                    $$"""
                    set -euo pipefail
                    sed -i -E "s/^const val VERSION = \"[^\"]+\"$/const val VERSION = \"$VERSION\"/" "$VERSION_FILE"
                    last="$(git describe --tags --abbrev=0 --match 'v[0-9]*' 2>/dev/null || true)"
                    range="${last:+$last..}HEAD"
                    {
                      echo "# Changelog"
                      echo
                      echo "## $VERSION - $(date -u +%Y-%m-%d)"
                      echo
                      echo "Changes since ${last:-the first commit}."
                      echo
                      git log --no-merges --format='- %s (%h)' "$range"
                      echo
                      [ -f CHANGELOG.md ] && sed '1{/^# Changelog$/d}' CHANGELOG.md
                    } > "$RUNNER_TEMP/CHANGELOG.md"
                    mv "$RUNNER_TEMP/CHANGELOG.md" CHANGELOG.md
                    mkdir -p "$RUNNER_TEMP/bump/$(dirname "$VERSION_FILE")"
                    cp CHANGELOG.md "$RUNNER_TEMP/bump/"
                    cp "$VERSION_FILE" "$RUNNER_TEMP/bump/$VERSION_FILE"
                    git diff --stat
                    """.trimIndent() + "\n",
            )
            uses(
                action =
                    uploadArtifact(
                        mapOf("name" to "bump", "path" to "\${{ runner.temp }}/bump", "if-no-files-found" to "error"),
                    ),
            )
        }

    val verify =
        job(
            id = "verify",
            needs = listOf(checks),
            runsOn = RunnerType.Custom("\${{ matrix.os }}"),
            timeoutMinutes = 120,
            _customArguments =
                mapOf(
                    "strategy" to
                        mapOf(
                            "fail-fast" to true,
                            "matrix" to
                                mapOf(
                                    "include" to
                                        listOf(
                                            // macos-15 is Apple Silicon.
                                            mapOf(
                                                "os" to "macos-15",
                                                "command" to "./gradlew macosArm64Test cliTest e2eTest --console=plain",
                                            ),
                                            mapOf(
                                                "os" to "ubuntu-latest",
                                                "prepare" to konanBootstrap,
                                                "command" to
                                                    "./gradlew -Prtok.allTargets=true linuxX64Test cliTest e2eTest -x ktlintCheck -x detekt --continue --console=plain",
                                            ),
                                        ),
                                ),
                        ),
                ) + bashDefaults,
        ) {
            setUp()
            run(name = "Test", command = "\${{ matrix.command }}")
        }

    val build =
        job(
            id = "build",
            name = "build \${{ matrix.target }}",
            needs = listOf(checks, verify),
            runsOn = RunnerType.Custom("\${{ matrix.os }}"),
            timeoutMinutes = 120,
            env = mapOf("TARGET" to "\${{ matrix.target }}", "TAG" to "\${{ needs.checks.outputs.tag }}"),
            _customArguments =
                mapOf(
                    "strategy" to
                        mapOf(
                            "fail-fast" to true,
                            "matrix" to
                                mapOf(
                                    "include" to
                                        listOf(
                                            mapOf(
                                                "os" to "macos-15",
                                                "target" to "macosArm64",
                                                "task" to ":rtok:linkReleaseExecutableMacosArm64",
                                                "gradle-flags" to "",
                                            ),
                                            mapOf(
                                                "os" to "ubuntu-latest",
                                                "target" to "linuxX64",
                                                "task" to ":rtok:linkReleaseExecutableLinuxX64",
                                                "gradle-flags" to "-Prtok.allTargets=true -x ktlintCheck -x detekt",
                                                "prepare" to konanBootstrap,
                                            ),
                                        ),
                                ),
                        ),
                ) + bashDefaults,
        ) {
            setUp()
            run(name = "Build", command = "./gradlew \${{ matrix.gradle-flags }} \${{ matrix.task }} --console=plain")
            run(
                name = "Smoke test",
                command =
                    $$"""
                    set -euo pipefail
                    bin="rtok/build/bin/$TARGET/releaseExecutable/rtok.kexe"
                    out="$("$bin" --version)"
                    echo "$out"
                    grep -qF "${TAG#v}" <<<"$out" || { echo "::error::$bin --version does not report ${TAG#v}"; exit 1; }
                    """.trimIndent() + "\n",
            )
            run(
                name = "Package",
                command =
                    $$"""
                    set -euo pipefail
                    name="rtok-$TAG-$TARGET"
                    stage="$RUNNER_TEMP/stage/$name"; mkdir -p "$stage" "$RUNNER_TEMP/dist"
                    cp "rtok/build/bin/$TARGET/releaseExecutable/rtok.kexe" "$stage/rtok"
                    cp README.md CHANGELOG.md "$stage/"
                    tar -C "$RUNNER_TEMP/stage" -czf "$RUNNER_TEMP/dist/$name.tar.gz" "$name"
                    cd "$RUNNER_TEMP/dist"
                    if command -v sha256sum >/dev/null; then sha256sum "$name.tar.gz" > "$name.tar.gz.sha256"
                    else shasum -a 256 "$name.tar.gz" > "$name.tar.gz.sha256"; fi
                    cat "$name.tar.gz.sha256"
                    echo "DIST_DIR=$RUNNER_TEMP/dist" >> "$GITHUB_ENV"
                    """.trimIndent() + "\n",
            )
            uses(
                action =
                    uploadArtifact(
                        mapOf("name" to "release-\${{ matrix.target }}", "path" to "\${{ env.DIST_DIR }}", "if-no-files-found" to "error"),
                    ),
            )
        }

    job(
        id = "release",
        needs = listOf(checks, verify, build),
        // Runs only when every verify and build job is green, and waits for the reviewer on the
        // `release` environment before it pushes anything.
        condition = "\${{ !inputs.dry-run && needs.verify.result == 'success' && needs.build.result == 'success' }}",
        runsOn = UbuntuLatest,
        timeoutMinutes = 15,
        environment = Environment("release"),
        permissions = mapOf(Permission.Contents to Mode.Write, Permission.PullRequests to Mode.Write),
        env = mapOf("TAG" to "\${{ needs.checks.outputs.tag }}", "VERSION" to "\${{ needs.checks.outputs.version }}"),
    ) {
        uses(action = checkout(mapOf("fetch-depth" to "0")))
        uses(action = downloadArtifact(bumpArtifact))
        uses(action = downloadArtifact(mapOf("pattern" to "release-*", "path" to "dist", "merge-multiple" to "true")))
        run(
            name = "Commit, tag, draft prerelease, bump PR",
            env = mapOf("GH_TOKEN" to "\${{ github.token }}"),
            command =
                $$"""
                set -euo pipefail
                git config user.name "github-actions[bot]"
                git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
                branch="release/$TAG"
                git switch -c "$branch"
                git add CHANGELOG.md "$VERSION_FILE"
                git commit -m "release: $VERSION"
                git tag -a "$TAG" -m "rtok $VERSION"
                git push origin "$branch" "refs/tags/$TAG"
                (cd dist && cat ./*.sha256 > SHA256SUMS)
                awk -v v="$VERSION" '$0 ~ "^## "v" " {on=1; next} /^## / && on {exit} on' CHANGELOG.md > "$RUNNER_TEMP/notes.md"
                gh release create "$TAG" --draft --prerelease --latest=false --verify-tag \
                  --title "rtok $VERSION" --notes-file "$RUNNER_TEMP/notes.md" \
                  dist/*.tar.gz dist/SHA256SUMS
                gh pr create --base main --head "$branch" --title "release: $VERSION" \
                  --body "Version.kt and CHANGELOG.md for the draft prerelease $TAG." \
                  || echo "::warning title=release::Actions may not open PRs here; open one by hand: $GITHUB_SERVER_URL/$GITHUB_REPOSITORY/compare/main...$branch"
                echo "Draft prerelease $TAG created; publish it by hand after review." >> "$GITHUB_STEP_SUMMARY"
                """.trimIndent() + "\n",
        )
    }
}
