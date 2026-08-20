package com.gotcha.agent.skills

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The five Termux skills bundled under [app/src/main/assets/skills] are the
 * model's defence against rediscovering Termux's limits one failure at a time
 * (slow mirrors, cross-uid isolation, Doze killing background processes,
 * glibc-only binaries that won't run on bionic). Each one must:
 *
 *  1. load from the bundled assets into [SkillRegistry.getAllSkills];
 *  2. target Termux *and* Gotcha, so they auto-inject both when Termux is in
 *     the foreground and when Gotcha is (the common chat case) — rather than
 *     only being discoverable via search_skills;
 *  3. require the [run_termux_command] tool, so they are auto-withheld on
 *     devices where Termux is not installed — the model never gets advice
 *     for a tool it cannot reach.
 *
 * The same id surface is consumed by [AgentEngine.buildEnvironmentString] and
 * the docs (RUNNING.md, docs/termux-setup.md), so a rename here is a
 * deliberate, coordinated change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TermuxSkillsBundledTest {

    private val expected = listOf(
        "termux_operations",
        "termux_repositories",
        "termux_filesystem",
        "termux_background",
        "termux_proot"
    )

    @Before
    fun setup() {
        SkillRegistry.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `every termux skill is loaded from bundled assets`() {
        val loaded = SkillRegistry.getAllSkills().map { it.id }.toSet()
        expected.forEach { id ->
            assertTrue(
                "Bundled skill '$id' did not load. Loaded ids: $loaded",
                id in loaded
            )
        }
    }

    @Test
    fun `every termux skill targets com_termux and is gated on run_termux_command`() {
        expected.forEach { id ->
            val skill = SkillRegistry.getSkillById(id)
            assertNotNull("Skill '$id' must be registered", skill)
            assertTrue(
                "Skill '$id' must target com.termux (and com.gotcha) so it auto-injects. Got: ${skill!!.targetPackageNames}",
                "com.termux" in skill.targetPackageNames && "com.gotcha" in skill.targetPackageNames
            )
            assertTrue(
                "Skill '$id' must require run_termux_command so it is " +
                    "withheld when Termux is not installed",
                "run_termux_command" in skill.requiresTools
            )
        }
    }

    @Test
    fun `termux skills are hidden when run_termux_command is gated off`() {
        // Regression guard: a skill that requires a tool the model cannot
        // currently call is a poisoned prompt — it costs tokens and invites
        // a doomed tool call. Re-confirming it here with the canonical
        // hiddenTools set.
        val hidden = setOf("run_termux_command")
        expected.forEach { id ->
            val skill = SkillRegistry.getSkillById(id)!!
            assertTrue(
                "Skill '$id' should be unavailable while run_termux_command is hidden",
                skill.isAvailable(hidden).not()
            )
        }
    }

    @Test
    fun `termux_filesystem is discoverable by sdcard keyword but hidden when tool is gated`() {
        // Locks in the user-facing contract: when the model calls
        // search_skills("sdcard") on a device with Termux set up, the
        // filesystem skill comes back; on a device without it, the skill
        // is suppressed by the requiresTools gate.
        //
        // Regression guard for the /sdcard keyword bug: a previous
        // version of the skill used only "\b/sdcard\b" as a keyword, but
        // the leading slash is a non-word character so \b does not match
        // /sdcard in normal text (only "bar/sdcard"). The model would
        // then fail to fetch the filesystem skill when the user's last
        // message mentioned /sdcard, and silently miss the cross-uid
        // bridge advice.
        val available = SkillRegistry.searchSkills("sdcard", hiddenTools = emptySet())
        assertTrue(
            "termux_filesystem must be discoverable by sdcard keyword when " +
                "run_termux_command is available. Got: ${available.map { it.id }}",
            available.any { it.id == "termux_filesystem" }
        )

        val hidden = SkillRegistry.searchSkills(
            "sdcard",
            hiddenTools = setOf("run_termux_command")
        )
        assertTrue(
            "termux_filesystem must be hidden from search_skills when its " +
                "required tool is gated. Got: ${hidden.map { it.id }}",
            hidden.none { it.id == "termux_filesystem" }
        )
    }
}
