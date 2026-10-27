// Port of the src/measure/skills_listing.rs test and the fixture half of tests/skill_listing.rs (T71.4).
@file:Suppress("MagicNumber")

package rtok.measure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillsListingTest {
    @Test fun measuresAvailableSkillsBlock() {
        val body =
            """{"model":"claude","messages":[],"system":[{"type":"text","text":"prefix\n<available_skills>\n<agent_skill fullPath=\"/a/x\">alpha skill</agent_skill>\n<agent_skill fullPath=\"/b/y\">beta has a longer description here</agent_skill>\n</available_skills>\nsuffix"}]}"""
        val m = SkillsListing.measure(body.encodeToByteArray())!!
        assertEquals(2, m.count)
        assertTrue(m.blockBytes > 80)
        assertTrue(m.framingBytesPerSkill in 21..119)
        assertNull(SkillsListing.measure("not json".encodeToByteArray()))
    }

    /** tests/fixtures/proxy/skills_listing_request.json: three listed skills. */
    @Test fun theProxyFixtureListsThreeSkills() {
        val skill = { path: String, desc: String -> "<agent_skill fullPath=\\\"$path\\\">$desc</agent_skill>\\n" }
        val text =
            "You are Claude Code.\\n\\n<available_skills>\\n" +
                skill("/Users/dev/.claude/skills/rtok/SKILL.md", "Token-saving rtok surfaces for coding agents.") +
                skill("/Users/dev/.claude/plugins/cache/slint/slint/1.0/skills/slint/SKILL.md", "Slint UI guidance and widget patterns.") +
                skill("/Users/dev/.claude/skills/dry-refactoring/SKILL.md", "Safe refactors with duplication checks before edits.") +
                "</available_skills>\\n"
        val body = """{"model":"claude-sonnet-4-20250514","max_tokens":1024,"system":[{"type":"text","text":"$text"}],"messages":[{"role":"user","content":"hi"}]}"""
        assertEquals(3, SkillsListing.measure(body.encodeToByteArray())!!.count)
    }
}
