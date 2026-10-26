// Port of src/agents/skill.rs (`SKILLS`, `SKILL_DESC_MAX`, `root`, `dest`, `label`, `sync`, `retire_legacy`) and
// src/agents/mod.rs `skill_src`: the skills this repo ships (`skills/<name>/`) copied into each host's documented root.
package rtok.agents

import rtok.agent.sdk.Apply
import rtok.agent.sdk.NO_CHANGES
import rtok.agent.sdk.SkillCopy
import rtok.config.Config
import rtok.sys.entryKind
import rtok.sys.exists
import rtok.sys.isDir
import rtok.sys.join
import rtok.sys.parent

object Skills {
    /** Installed and removed together; every name but the hub `rtok` starts with `rtok-` so it cannot collide (T380). */
    val SKILLS = listOf("rtok", "rtok-worktrees", "rtok-tasks")

    /** Longest `description:` a skill listing should carry; `doctor` flags longer ones. */
    const val SKILL_DESC_MAX = 120

    /** The unprefixed install from before T380, retired on install only when provably ours. */
    private const val LEGACY_SKILL = "worktrees"

    /** User skill root for a host that documents the Agent Skills format; null leaves the host untouched. */
    fun root(
        host: String,
        cfg: Config,
    ): String? {
        val home = McpPaths.home()
        val s = cfg.setup
        return when (host) {
            "claude" -> join(home, ".claude/skills")

            "cursor" -> join(home, ".cursor/skills")

            "codex" -> join(home, ".codex/skills")

            "opencode" -> beside(s.opencode.configPath)

            "copilot" -> join(s.copilot.dir, "skills")

            "commandcode" -> join(s.commandcode.dir, "skills")

            // T234: pi reads user skills beside its extensions tree.
            "pi" -> beside(s.pi.extensionsPath)

            // T91.2: Antigravity and its CLI each read their own root beside their plugin roots; `antigravity-cli` is
            // the CLI variant's key, not a host id.
            "antigravity" -> beside(s.antigravity.pluginsPath)

            "antigravity-cli" -> beside(s.antigravity.cliPluginsPath)

            else -> null
        }
    }

    private fun beside(path: String) = parent(path)?.let { join(it, "skills") }

    /** Where `skills/<name>` lands for [host]. */
    fun dest(
        host: String,
        cfg: Config,
        name: String,
    ): String? = root(host, cfg)?.let { join(it, name) }

    /** How the root reads to a person. */
    fun label(host: String): String? =
        when (host) {
            "claude" -> "~/.claude/skills"
            "cursor" -> "~/.cursor/skills"
            "codex" -> "~/.codex/skills"
            "opencode" -> "~/.config/opencode/skills"
            "copilot" -> "~/.copilot/skills"
            "commandcode" -> "~/.commandcode/skills"
            "pi" -> "~/.pi/agent/skills"
            "antigravity" -> "~/.gemini/config/skills"
            "antigravity-cli" -> "~/.gemini/antigravity-cli/skills"
            else -> null
        }

    /** A shipped skill tree: the top-level hub, else the pi-bundled copy older ketch archives carry. */
    fun src(
        name: String,
        plugins: PluginSource,
    ): String = plugins.of("skills/$name").takeIf(::exists) ?: plugins.of("plugins/pi/skills/$name")

    /** Copy or remove every shipped skill for [host]: one line per skill that changes, [NO_CHANGES] when none does. */
    fun sync(
        host: String,
        cfg: Config,
        remove: Boolean,
        plugins: PluginSource = ShippedPlugins,
    ): String {
        val root = root(host, cfg) ?: return NO_CHANGES
        val apply = applyOf(cfg)
        val lines =
            SKILLS.map { name -> one(host, root, name, src(name, plugins), apply, remove) }.filter { it != NO_CHANGES } +
                // Install only: a later remove has no `worktrees` in SKILLS to take back.
                listOfNotNull(if (remove) null else retireLegacy(host, cfg, root, plugins))
        return if (lines.isEmpty()) NO_CHANGES else lines.joinToString("\n")
    }

    /** One skill; an install whose hub tree is not on this machine (a bare dev build) has nothing to copy. */
    private fun one(
        host: String,
        root: String,
        name: String,
        src: String,
        apply: Apply,
        remove: Boolean,
    ): String {
        val copy = copy(host, root, name, src)
        return if (remove || isDir(src)) copy.run(apply, remove) else NO_CHANGES
    }

    private fun copy(
        host: String,
        root: String,
        name: String,
        src: String,
    ) = SkillCopy(src, join(root, name), label(host)?.let { "$it/$name" })

    /** Drop the pre-T380 `worktrees` install when [SkillCopy] proves it ours; anything else stays and is named once. */
    private fun retireLegacy(
        host: String,
        cfg: Config,
        root: String,
        plugins: PluginSource,
    ): String? {
        val dest = join(root, LEGACY_SKILL)
        if (entryKind(dest) == null) return null
        val line = copy(host, root, LEGACY_SKILL, src("rtok-worktrees", plugins)).run(applyOf(cfg), true)
        return if (line != NO_CHANGES) line else "leave $dest (not an rtok skill; remove by hand)"
    }
}
