package com.gotcha.ui.tour

import android.content.Context
import com.gotcha.data.Settings
import com.gotcha.ui.isAccessibilityGranted
import com.gotcha.ui.isOverlayGranted

/**
 * Somewhere the tour can put a coach mark. The host translates these into its own
 * navigation — route, settings sub-page, drawer state — so the step list never
 * has to know how the app is wired together.
 */
enum class TourPlace {
    /** The chat screen with the navigation drawer open. */
    CHAT_DRAWER,

    /** The chat screen itself. */
    CHAT,

    /** The settings category list. */
    SETTINGS_HOME,
    AI_CONFIG,
    PERMISSIONS,
    PERSONAL_INFO
}

/**
 * One instruction in the guided setup.
 *
 * The design rule the whole flow follows: a step is finished when the app's own
 * state says so — a session token exists, a permission is granted, the user has
 * arrived where the next step lives — and never because a "Next" button was
 * pressed. That is what stops the tour from claiming a device is set up when it
 * is not, and what lets the user wander off to Android Settings and come back to
 * a tour that has kept up with them.
 *
 * @param place where this step's coach mark is drawn.
 * @param autoNavigate whether the host should travel to [place] on the tour's
 *   behalf. False for the steps where making the journey *is* the lesson: those
 *   wait for the user to arrive, which is what teaches them the way back.
 * @param anchor the control to cut out of the scrim. Null draws the card in the
 *   middle of the screen, for steps that describe a screen rather than a button.
 * @param hint secondary text for the awkward cases — chiefly the OEM-specific
 *   path to a system settings toggle.
 * @param requiresAnchor when true, the step is skipped outright if [anchor] is
 *   not on screen. Used for branches that only exist under some configurations.
 * @param ackLabel label for the button that dismisses the step without doing the
 *   thing. Null means the step can only be finished by acting on it.
 * @param isDone the state that ends the step, re-read on every resume.
 */
data class TourStep(
    val id: String,
    val place: TourPlace,
    val title: String,
    val body: String,
    val autoNavigate: Boolean = true,
    val anchor: TourAnchor? = null,
    val hint: String? = null,
    val requiresAnchor: Boolean = false,
    val ackLabel: String? = null,
    val isDone: (Settings, Context) -> Boolean = { _, _ -> false }
)

/**
 * The guided setup, in order.
 *
 * Deliberately short. Everything here is either something the app cannot work
 * without or something the user would never find on their own; anything that is
 * merely nice to know belongs in the screen it lives on, not in a queue the user
 * has to clear before they can type their first message.
 */
fun defaultTourSteps(): List<TourStep> = listOf(
    TourStep(
        id = "open_settings",
        place = TourPlace.CHAT_DRAWER,
        anchor = TourAnchor.DRAWER_SETTINGS,
        title = "Everything lives behind here",
        body = "Swipe in from the left edge any time to reach this menu. Tap Settings to start.",
        hint = "Tip: the same swipe works from anywhere in the chat."
    ),
    TourStep(
        id = "open_ai_config",
        place = TourPlace.SETTINGS_HOME,
        autoNavigate = false,
        anchor = TourAnchor.SETTINGS_AI_CONFIG,
        title = "Give Gotcha a brain",
        body = "AI Configuration is where the model lives. Open it — you'll come back here to " +
            "change models later."
    ),
    TourStep(
        id = "choose_provider",
        place = TourPlace.AI_CONFIG,
        anchor = TourAnchor.AI_PROVIDER,
        title = "Pick who does the thinking",
        body = "Samosa AI gives you a model, speech and transcription on a free daily allowance — " +
            "no keys to paste. Choose OpenAI-compatible instead if you'd rather bring your own.",
        ackLabel = "Got it"
    ),
    TourStep(
        id = "samosa_sign_in",
        place = TourPlace.AI_CONFIG,
        anchor = TourAnchor.AI_SAMOSA_SIGN_IN,
        requiresAnchor = true,
        title = "Sign in once",
        body = "One Google account covers the model, the voice and the transcription.",
        ackLabel = "Not now",
        isDone = { settings, _ -> settings.isSamosaAuthenticated }
    ),
    TourStep(
        id = "save_ai_config",
        place = TourPlace.AI_CONFIG,
        anchor = TourAnchor.AI_SAVE,
        title = "Save it",
        body = "Nothing on this page takes effect until you do.",
        ackLabel = "Skip",
        isDone = { settings, _ -> settings.hasUsableModel }
    ),
    TourStep(
        id = "open_permissions",
        place = TourPlace.SETTINGS_HOME,
        anchor = TourAnchor.SETTINGS_PERMISSIONS,
        title = "Now decide what it may touch",
        body = "Open Permissions. Two of them are worth granting now; the rest can wait."
    ),
    TourStep(
        id = "grant_accessibility",
        place = TourPlace.PERMISSIONS,
        title = "Accessibility is the important one",
        body = "It's what lets Gotcha read the screen and tap for you. Without it the assistant " +
            "can answer questions but cannot do anything. Turn on Accessibility under System Access.",
        hint = "On some phones this lives under Settings › Additional settings › Accessibility › " +
            "Installed apps.",
        ackLabel = "Skip for now",
        isDone = { _, context -> isAccessibilityGranted(context) }
    ),
    TourStep(
        id = "grant_overlay",
        place = TourPlace.PERMISSIONS,
        title = "And one for the floating ball",
        body = "\"Display Over Apps\" lets the assistive ball and Screen Lens appear on top of " +
            "other apps.",
        ackLabel = "Skip for now",
        isDone = { _, context -> isOverlayGranted(context) }
    ),
    TourStep(
        id = "open_personal_info",
        place = TourPlace.SETTINGS_HOME,
        anchor = TourAnchor.SETTINGS_PERSONAL_INFO,
        title = "Last thing: who are you?",
        body = "Open Personal Info. What you put here reaches every reply — your language, your " +
            "units, your currency."
    ),
    TourStep(
        id = "fill_personal_info",
        place = TourPlace.PERSONAL_INFO,
        title = "Tell it your name",
        body = "All of this is optional, and all of it makes the answers fit you better. Fill in " +
            "what you like, then save.",
        ackLabel = "Skip",
        isDone = { settings, _ -> settings.userName.isNotBlank() }
    ),
    TourStep(
        id = "finish",
        place = TourPlace.CHAT,
        title = "That's the tour",
        body = "Anything you skipped is waiting in Settings, and Feature Tour there replays this " +
            "whenever you want it.",
        ackLabel = "Start using Gotcha"
    )
)
