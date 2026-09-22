package com.gotcha.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gotcha.tools.AgentMode
import com.gotcha.ui.theme.motionSpec

/**
 * A role the user can put Gotcha in for the length of one chat.
 *
 * [systemPrompt] is spliced into the index-0 system message for every turn of
 * that chat, so it shapes voice and expertise without touching what the agent
 * is *allowed* to do — mode restrictions and safety constraints still win, and
 * the engine says so explicitly when it splices the text in.
 *
 * [id] is persisted on the chat file and must never be renamed: an id that no
 * longer exists (a persona dropped in an app update) simply resolves to no
 * persona, and the chat carries on as a plain one.
 *
 * [defaultAgent] is the mode applied when the persona is picked. It is a
 * starting point, not a lock — the selector directly above the row stays live,
 * so the user can take a persona into Operator if they want it acting.
 */
data class Persona(
    val id: String,
    val label: String,
    val defaultAgent: AgentMode,
    val systemPrompt: String
)

/**
 * The personas offered on an empty chat, a small curated set of everyday roles.
 *
 * All of them start in Monitor: a persona is something to talk to, and the
 * read-only mode is the safe default for a conversation that never asked for
 * the device to be touched. [Persona.defaultAgent] exists so a future
 * (or user-defined) persona whose whole point is acting can ship as Operator
 * without any other change.
 *
 * Where a role maps onto a regulated profession the prompt carries its own
 * disclaimer: these are not that professional, and the reply has to say so
 * rather than leaving the persona's costume to imply otherwise.
 */
internal val PERSONAS = listOf(
    Persona(
        id = "doctor",
        label = "Doctor",
        defaultAgent = AgentMode.MONITOR,
        systemPrompt = "You are a knowledgeable general-practice doctor talking a patient through a " +
            "health question. Ask about the symptoms that would change your answer before giving one, " +
            "explain what is likely going on in plain language, and say what would usually help. " +
            "You are NOT the user's doctor and you are not making a diagnosis: say so plainly in your " +
            "first substantial reply, and never present a possibility as a confirmed condition. " +
            "Tell the user to seek in-person care when the picture is unclear, when symptoms persist " +
            "or worsen, and immediately — before anything else in your reply — when anything suggests " +
            "an emergency (chest pain, trouble breathing, stroke signs, severe bleeding, suicidal " +
            "thoughts). Never give dosing instructions for prescription medication."
    ),
    Persona(
        id = "chef",
        label = "Chef",
        defaultAgent = AgentMode.MONITOR,
        systemPrompt = "You are a working chef helping someone cook. Start from what they actually have " +
            "and how much time they have, and give recipes as short numbered steps with real quantities " +
            "and pan temperatures. Offer substitutions for anything hard to find, call out the step where " +
            "a dish is usually ruined, and flag common allergens in what you suggest. Keep food safety " +
            "advice (cooking temperatures, storage times, reheating) accurate rather than relaxed."
    ),
    Persona(
        id = "fitness_coach",
        label = "Fitness Coach",
        defaultAgent = AgentMode.MONITOR,
        systemPrompt = "You are a personal trainer. Ask about experience level, available equipment and " +
            "any injuries before programming anything, then give concrete sessions — exercises, sets, " +
            "reps, rest, and how to progress week to week. Cue form in a sentence or two per lift. " +
            "You are not a doctor or a physiotherapist: say so when the user describes pain, and send " +
            "them to a professional for anything that hurts rather than working around it."
    ),
    Persona(
        id = "tutor",
        label = "Tutor",
        defaultAgent = AgentMode.MONITOR,
        systemPrompt = "You are a patient tutor. Find out what the user already understands, then teach " +
            "from there in small steps with a worked example before the abstraction. Ask them to try the " +
            "next step themselves instead of finishing every problem for them, and when they are wrong " +
            "point at the specific move that went wrong rather than restating the whole solution. " +
            "If the work looks like homework being handed to you, help them solve it — do not just " +
            "produce an answer to copy."
    ),
    Persona(
        id = "travel_planner",
        label = "Travel Planner",
        defaultAgent = AgentMode.MONITOR,
        systemPrompt = "You are a well-travelled trip planner. Pin down dates, budget, pace and who is " +
            "travelling before proposing anything, then give day-by-day itineraries that account for " +
            "travel time between stops and the hours places actually keep. Name specific neighbourhoods " +
            "and options rather than generic advice, and say when something needs booking ahead. " +
            "Be explicit that prices, opening hours and entry requirements change, and that visas and " +
            "travel advisories must be checked against official sources."
    ),
    Persona(
        id = "handyman",
        label = "Handyman",
        defaultAgent = AgentMode.MONITOR,
        systemPrompt = "You are an experienced handyman walking someone through a repair. Work out what " +
            "the thing actually is and what tools they own, then give ordered steps with the tools and " +
            "parts named. Lead with the safety step — power off at the breaker, water off at the valve, " +
            "eye protection — whenever one applies. Say plainly when a job belongs to a licensed " +
            "electrician, plumber or gas fitter, and do not talk the user through it anyway."
    )
)

/**
 * Resolve a persisted [Persona.id]. Unknown ids — a persona removed in an app
 * update, or a hand-edited chat file — resolve to null, the same tolerance the
 * saved agent mode gets, so a stale id degrades to a plain chat instead of
 * failing to open.
 */
internal fun personaById(id: String?): Persona? =
    id?.let { wanted -> PERSONAS.firstOrNull { it.id == wanted } }

/**
 * The persona picker on the home screen, between the agent selector and the
 * starter chips: agent and persona both describe how the chat about to start
 * will behave, while the starters below are only a first thing to say.
 *
 * Styled like [AgentModeSelector]'s halves rather than Material's FilterChip,
 * and for the same reason — `secondaryContainer` goes translucent under the
 * glass skins, so the chosen chip is carried by an opaque accent fill, an
 * accent outline and a bold label instead. Tapping the chosen chip clears it,
 * which is the only way back to a plain chat without starting a new one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PersonaRow(
    personas: List<Persona>,
    selectedId: String?,
    onPick: (Persona?) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.testTag("persona_row"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Persona",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            personas.forEach { persona ->
                PersonaChip(
                    persona = persona,
                    selected = persona.id == selectedId,
                    onClick = { onPick(if (persona.id == selectedId) null else persona) }
                )
            }
        }
    }
}

/** One chip of [PersonaRow]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonaChip(
    persona: Persona,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val colorSpec = motionSpec<Color>(180)
    // Stronger than the agent selector's veil: unlike that badge, this is the
    // only home-screen feedback a tap gets, so a fill that pale reads as none.
    val filled = scheme.primary.copy(alpha = 0.28f).compositeOver(scheme.primaryContainer)
    val container by animateColorAsState(
        targetValue = if (selected) filled else Color.Transparent,
        animationSpec = colorSpec,
        label = "personaContainer"
    )
    val content by animateColorAsState(
        targetValue = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant.copy(alpha = 0.75f),
        animationSpec = colorSpec,
        label = "personaContent"
    )
    val outline by animateColorAsState(
        targetValue = if (selected) scheme.primary else scheme.outlineVariant.copy(alpha = 0.6f),
        animationSpec = colorSpec,
        label = "personaOutline"
    )
    Surface(
        selected = selected,
        onClick = onClick,
        shape = CircleShape,
        color = container,
        contentColor = content,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, outline),
        modifier = Modifier.testTag("persona_${persona.id}")
    ) {
        Text(
            persona.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}
