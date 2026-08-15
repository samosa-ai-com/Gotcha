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
 *  2. target [Termux's package name], so they auto-inject when Termux is in
 *     the foreground (rather than only being discoverable via search_skills);
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
            assertEquals(
                "Skill '$id' must target com.termux so it auto-injects",
                listOf("com.termux"),
                skill!!.targetPackageNames
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
}
