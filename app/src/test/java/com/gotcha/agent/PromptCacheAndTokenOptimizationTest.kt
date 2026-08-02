package com.gotcha.agent

import com.gotcha.agent.skills.Skill
import com.gotcha.agent.skills.SkillPromptBuilder
import com.gotcha.llm.ChatMessage
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PromptCacheAndTokenOptimizationTest {

    @Test
    fun `SkillPromptBuilder formats compact summary index with search skills directive`() {
        val skill = Skill(
            id = "test_skill",
            description = "A test skill for automation.",
            instructions = "Line 1\nLine 2\nLine 3"
        )
        val msg = SkillPromptBuilder.build("com.example.app", listOf(skill), emptySet())
        assertNotNull(msg)
        val text = msg!!.textContent

        assertTrue("Should contain available-skills tag", text.contains("<available-skills>"))
        assertTrue("Should contain skill id", text.contains("test_skill"))
        assertTrue("Should contain skill description", text.contains("A test skill for automation."))
        assertFalse("Should NOT contain full instruction body", text.contains("Line 1\nLine 2\nLine 3"))
        assertTrue("Should contain search_skills directive", text.contains("search_skills tool"))
    }

    @Test
    fun `SkillPromptBuilder produces valid XML nesting when only community skills exist`() {
        val skill = Skill(
            id = "community_skill_1",
            description = "A community contributed skill.",
            instructions = "Instructions here"
        )
        val msg = SkillPromptBuilder.build("com.example.app", listOf(skill), setOf("community_skill_1"))
        assertNotNull(msg)
        val text = msg!!.textContent

        assertTrue("Should open with available-skills", text.startsWith("<available-skills>"))
        assertTrue("Should contain community-skills header", text.contains("<community-skills>"))
        assertTrue("Should contain prompt injection warning", text.contains("advisory guidance"))
        assertTrue("Should close community-skills", text.contains("</community-skills>"))
        assertTrue("Should close available-skills at end", text.trimEnd().endsWith("</available-skills>"))
    }

    @Test
    fun `SkillPromptBuilder returns null when skills list is empty`() {
        val msg = SkillPromptBuilder.build("com.example.app", emptyList(), emptySet())
        assertTrue("Should return null for empty skills list", msg == null)
    }

    @Test
    fun `SkillPromptBuilder excludes skills already fetched in history`() {
        val skill1 = Skill(id = "whatsapp_calling", description = "Call skills", instructions = "x")
        val skill2 = Skill(id = "whatsapp_messaging", description = "Message skills", instructions = "y")

        val history = listOf(
            ChatMessage(role = "user", content = kotlinx.serialization.json.JsonPrimitive("Call Mom")),
            ChatMessage(
                role = "tool",
                content = kotlinx.serialization.json.JsonPrimitive(
                    "Found 1 skills matching 'calling':\n\nSkill [whatsapp_calling]:\nLine 1"
                )
            )
        )
        val fetched = SkillPromptBuilder.extractFetchedSkillIds(history)
        assertEquals(setOf("whatsapp_calling"), fetched)

        val msg = SkillPromptBuilder.build(
            currentPackage = "com.whatsapp",
            activeSkills = listOf(skill1, skill2),
            communityIds = emptySet(),
            recentlyFetchedSkillIds = fetched
        )
        assertNotNull(msg)
        val text = msg!!.textContent
        assertFalse("Should exclude whatsapp_calling since it was already fetched", text.contains("whatsapp_calling"))
        assertTrue("Should include whatsapp_messaging", text.contains("whatsapp_messaging"))
    }

    @Test
    fun `SkillPromptBuilder extractFetchedSkillIds handles malformed or non-matching tool messages safely`() {
        val history = listOf(
            ChatMessage(role = "user", content = kotlinx.serialization.json.JsonPrimitive("hello")),
            ChatMessage(role = "tool", content = kotlinx.serialization.json.JsonPrimitive("Random tool result")),
            ChatMessage(role = "tool", content = kotlinx.serialization.json.JsonPrimitive(""))
        )
        val fetched = SkillPromptBuilder.extractFetchedSkillIds(history)
        assertTrue("Should return empty set for non-matching tool messages", fetched.isEmpty())
    }

    @Test
    fun `Skill matchesIntent performs case-insensitive word-boundary matching`() {
        val skill = Skill(
            id = "call_skill",
            instructions = "x",
            keywords = listOf("call")
        )
        assertTrue("Should match 'call'", skill.matchesIntent("Call John"))
        assertTrue("Should match lower 'call'", skill.matchesIntent("can you call mom"))
        assertFalse("Should NOT match substring inside 'recall'", skill.matchesIntent("please recall this message"))
        assertFalse("Should NOT match substring inside 'vocaller'", skill.matchesIntent("vocaller app"))
    }

    @Test
    fun `SkillPromptBuilder filters skills by user intent keywords`() {
        val callSkill = Skill(
            id = "whatsapp_call",
            description = "Call features",
            instructions = "x",
            keywords = listOf("call", "phone", "ring")
        )
        val msgSkill = Skill(
            id = "whatsapp_msg",
            description = "Messaging features",
            instructions = "y",
            keywords = listOf("message", "text", "send")
        )

        val callMsg = SkillPromptBuilder.build(
            currentPackage = "com.whatsapp",
            activeSkills = listOf(callSkill, msgSkill),
            communityIds = emptySet(),
            lastUserMessage = "Can you call Alex on WhatsApp?"
        )
        assertNotNull(callMsg)
        assertTrue(callMsg!!.textContent.contains("whatsapp_call"))
        assertFalse(callMsg.textContent.contains("whatsapp_msg"))
    }

    @Test
    fun `Skill matchesIntent handles empty and blank text correctly`() {
        val skillWithKw = Skill(id = "test_kw", instructions = "x", keywords = listOf("call"))
        val skillNoKw = Skill(id = "test_nokw", instructions = "x", keywords = emptyList())

        assertTrue("Skill with keywords matches empty text", skillWithKw.matchesIntent(""))
        assertTrue("Skill with keywords matches blank text", skillWithKw.matchesIntent("   "))
        assertTrue("Skill without keywords matches empty text", skillNoKw.matchesIntent(""))
    }

    @Test
    fun `SkillPromptBuilder extractLastHumanUserMessage skips synthetic screenshot observations`() {
        val history = listOf(
            ChatMessage(
                role = "user",
                content = kotlinx.serialization.json.JsonPrimitive("Send a message on WhatsApp")
            ),
            ChatMessage(
                role = "assistant",
                content = kotlinx.serialization.json.JsonPrimitive("Opening app")
            ),
            ChatMessage(
                role = "user",
                content = kotlinx.serialization.json.JsonPrimitive("[Screen State]\nTurn observation")
            ),
            ChatMessage(
                role = "user",
                content = kotlinx.serialization.json.JsonPrimitive("[Previous screen observation removed]")
            )
        )

        val lastHumanMsg = SkillPromptBuilder.extractLastHumanUserMessage(history)
        assertEquals("Send a message on WhatsApp", lastHumanMsg)
    }

    @Test
    fun `SkillPromptBuilder SKILL_RESULT_REGEX matches ToolExecutor search_skills format`() {
        val toolOutput = "Found 2 skills matching 'test':\n\nSkill [skill_a]:\nLine 1\n\nSkill [skill_b]:\nLine 2"
        val matches = SkillPromptBuilder.SKILL_RESULT_REGEX.findAll(toolOutput).map { it.groupValues[1] }.toList()
        assertEquals(listOf("skill_a", "skill_b"), matches)
    }

    @Test
    fun `SkillPromptBuilder buildFromHistory integrates history extraction and intent filtering`() {
        val skillCall = Skill(id = "call_skill", instructions = "x", keywords = listOf("call"))
        val skillMsg = Skill(id = "msg_skill", instructions = "y", keywords = listOf("send"))

        val history = listOf(
            ChatMessage(
                role = "user",
                content = kotlinx.serialization.json.JsonPrimitive("Please call Alex")
            ),
            ChatMessage(
                role = "tool",
                content = kotlinx.serialization.json.JsonPrimitive(
                    "Found 1 skills:\n\nSkill [call_skill]:\nBody"
                )
            )
        )

        val msg = SkillPromptBuilder.buildFromHistory(
            currentPackage = "com.whatsapp",
            activeSkills = listOf(skillCall, skillMsg),
            communityIds = emptySet(),
            history = history
        )

        // call_skill is excluded because it's in history; msg_skill is excluded because user said "call", not "send"
        assertTrue("Should return null when all skills are excluded by history or intent", msg == null)
    }

    @Test
    fun `SkillPromptBuilder demotes unqualified wildcard skills to search-only`() {
        val wildcardNoKw = Skill(
            id = "wildcard_unqualified",
            targetPackageNames = listOf("*"),
            description = "Generic wildcard skill",
            instructions = "x",
            keywords = emptyList()
        )
        val wildcardWithKw = Skill(
            id = "wildcard_qualified",
            targetPackageNames = listOf("*"),
            description = "Specific wildcard skill",
            instructions = "y",
            keywords = listOf("special")
        )

        val result1 = SkillPromptBuilder.build(
            currentPackage = "com.example.any",
            activeSkills = listOf(wildcardNoKw, wildcardWithKw),
            communityIds = emptySet(),
            lastUserMessage = "General task"
        )
        assertTrue("Should return null when no wildcard skills match intent", result1 == null)

        val result2 = SkillPromptBuilder.build(
            currentPackage = "com.example.any",
            activeSkills = listOf(wildcardNoKw, wildcardWithKw),
            communityIds = emptySet(),
            lastUserMessage = "Do a special action"
        )
        assertNotNull(result2)
        assertFalse(result2!!.textContent.contains("wildcard_unqualified"))
        assertTrue(result2.textContent.contains("wildcard_qualified"))
    }

    @Test
    fun `AgentEngine cullOldObservations retains 4 most recent screen observations and culls older ones`() {
        val messages = mutableListOf<ChatMessage>()
        // Add 6 screen observation messages
        for (i in 1..6) {
            val textPart = buildJsonObject {
                put("type", "text")
                put("text", "[Screen State]\nTurn $i observation\n── UI Elements ──")
            }
            val imagePart = buildJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", "data:image/jpeg;base64,abc$i") }
            }
            messages.add(
                ChatMessage(
                    role = "user",
                    content = buildJsonArray {
                        add(textPart)
                        add(imagePart)
                    }
                )
            )
        }

        val culled = AgentEngine.cullOldObservations(messages)
        assertEquals(6, culled.size)

        // Turns 1 and 2 (older than 4 most recent) should be culled
        assertTrue(culled[0].textContent.contains("[Previous screen observation removed"))
        assertTrue(culled[1].textContent.contains("[Previous screen observation removed"))

        // Turns 3, 4, 5, 6 (the 4 most recent) should be retained
        assertTrue(culled[2].textContent.contains("Turn 3"))
        assertTrue(culled[3].textContent.contains("Turn 4"))
        assertTrue(culled[4].textContent.contains("Turn 5"))
        assertTrue(culled[5].textContent.contains("Turn 6"))
    }

    @Test
    fun `AgentEngine systemPromptMessage combines instructions and environment into Index 0 System message`() {
        com.gotcha.testsupport.FakeAndroidKeyStore.setUp()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = com.gotcha.data.ChatHistoryRepository(context)
        val engine = AgentEngine(
            appContext = context,
            events = object : AgentEvents {
                override fun onUi(
                    kind: MessageKind,
                    text: String,
                    imageBase64: String?,
                    subAgentSteps: List<String>,
                    reasoningContent: String?
                ) {}
                override fun onActivity(activity: String?) {}
                override fun onTokenCount(totalTokens: Int) {}
                override fun onAssistantReply(text: String) {}
                override fun onSubAgentUpdate(running: String?, currentAction: String?) {}
                override fun onPermissionRequest(marker: String) {}
                override suspend fun awaitQuestionAnswer(question: PendingQuestion): String = ""
                override suspend fun awaitConfirmation(toolNames: List<String>, description: String): Boolean = true
            },
            historyRepository = repository,
            settingsProvider = { com.gotcha.data.Settings() },
            clientProvider = { null }
        )
        val msg = engine.systemPromptMessage(com.gotcha.tools.AgentMode.OPERATOR)
        assertEquals("system", msg.role)
        val text = msg.textContent
        assertTrue("Should contain core Operator instructions", text.contains("You are Gotcha"))
        assertTrue("Should contain env block", text.contains("<env>"))
        assertTrue("Should contain device model info", text.contains("Device model:"))
    }
}
