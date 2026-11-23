package com.gotcha.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.gotcha.R
import com.gotcha.auth.ModelAccess
import com.gotcha.data.DEFAULT_API_TIMEOUT_SECONDS
import com.gotcha.data.DEFAULT_MAX_CONTEXT_TOKENS
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.telemetry.FeatureEvents
import com.gotcha.telemetry.SetupAction
import com.gotcha.telemetry.Telemetry
import com.gotcha.ui.theme.SkinExposedDropdownMenu
import com.gotcha.ui.tour.TourAnchor
import com.gotcha.ui.tour.tourAnchor
import com.gotcha.upsell.UpsellReason
import kotlinx.coroutines.launch

/**
 * The AI Configuration page: which LLM backend to talk to, which models to use
 * for the main agent and its sub-agents, and the agent loop's limits.
 *
 * Only the three things a working install needs — provider, credentials, main
 * model — are on the page itself. The sub-agent and navigator overrides, the loop
 * limits and the cache-clearing buttons sit inside a collapsed
 * [SettingsAdvancedSection]: they are worth having, but not worth scrolling past
 * on the way to Save.
 *
 * Saves write only the fields on this page (see [SettingsScreen]'s `onSave`), so
 * edits left half-finished on another page are never dragged into storage here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiConfigScreen(
    load: () -> Settings,
    onSave: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit,
    onTestConnection: suspend (Settings) -> Result<String>,
    onRefreshChatModels: suspend (Settings) -> Result<List<String>> = {
        Result.failure(Exception("Not available"))
    },
    /**
     * The models the Samosa AI plan may call, or null when nothing is locked
     * (see [com.gotcha.auth.ModelAccess]). Only asked while Samosa AI is the provider.
     */
    onFetchAllowedModels: suspend () -> Set<String>? = { null },
    onSamosaSignIn: suspend () -> Result<Pair<String, String>> = {
        Result.failure(Exception("Not available"))
    },
    /**
     * Google sign-in, when the account website is the main sign-in. Null when
     * Google is the only sign-in (then [onSamosaSignIn] is Google).
     */
    onSamosaSignInWithGoogle: (suspend () -> Result<Pair<String, String>>)? = null,
    /** Fires when the Samosa session changed outside this screen; it re-reads it. */
    samosaSessionChanges: kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.emptyFlow(),
) {
    val initial = remember { load() }
    var provider by remember { mutableStateOf(initial.provider) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    // Samosa auth state, kept live as the user signs in / out.
    var samosaToken by remember { mutableStateOf(initial.samosaSessionToken) }
    var samosaEmail by remember { mutableStateOf(initial.samosaEmail) }
    var samosaBusy by remember { mutableStateOf(false) }
    var subAgentModel by remember { mutableStateOf(initial.subAgentModel) }
    var navigatorModel by remember { mutableStateOf(initial.navigatorModel) }
    var maxToolRounds by remember { mutableStateOf(initial.maxToolRounds.toString()) }
    var maxRepeatedToolCalls by remember { mutableStateOf(initial.maxRepeatedToolCalls.toString()) }
    var maxNavigationToolCalls by remember { mutableStateOf(initial.maxNavigationToolCalls.toString()) }
    var maxConsecutiveDelegations by remember { mutableStateOf(initial.maxConsecutiveDelegations.toString()) }
    var maxContextTokens by remember { mutableStateOf(initial.maxContextTokens.toString()) }
    var apiTimeoutSeconds by remember { mutableStateOf(initial.apiTimeoutSeconds.toString()) }

    var availableChatModels by remember { mutableStateOf<List<String>>(emptyList()) }
    // Null: nothing locked (another provider, a plan without a list, or not known yet).
    var allowedModels by remember { mutableStateOf<Set<String>?>(null) }
    val openUpsell = LocalUpsell.current
    var showKey by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val localContext = LocalContext.current
    var testing by remember { mutableStateOf(false) }
    var refreshingChatModels by remember { mutableStateOf(false) }

    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var subAgentModelExpanded by remember { mutableStateOf(false) }
    var navigatorModelExpanded by remember { mutableStateOf(false) }

    val overlay = rememberSettingsOverlayState()
    val scope = rememberCoroutineScope()

    // The account, its credits and log out live on the Account screen (from
    // the drawer); this page only needs to know whether the user is signed in.
    SamosaSessionSync(
        sessionChanges = samosaSessionChanges,
        onSessionChanged = {
            val current = load()
            samosaToken = current.samosaSessionToken
            samosaEmail = current.samosaEmail
        },
        onResumed = {}
    )

    /** Runs one sign-in attempt and shows how it went. */
    fun runSamosaSignIn(startStatus: Int, signIn: suspend () -> Result<Pair<String, String>>) {
        samosaBusy = true
        status = localContext.getString(startStatus)
        scope.launch {
            val result = signIn()
            result.onSuccess { (email, token) ->
                samosaEmail = email
                samosaToken = token
                status = localContext.getString(R.string.samosa_signed_in_as, email)
            }.onFailure { e ->
                status = e.message ?: localContext.getString(R.string.samosa_sign_in_failed)
            }
            samosaBusy = false
        }
    }

    /**
     * This page's fields, copied onto [base].
     *
     * Deliberately does not write `samosaSessionToken` / `samosaEmail`: those are
     * owned by `SamosaAuthManager`, which persists them itself on sign-in and
     * clears them on a 401. Writing the form's copy back would resurrect a
     * session that expired while this page was open.
     */
    fun applyAiConfig(base: Settings) = base.copy(
        provider = provider,
        apiKey = apiKey.trim(),
        baseUrl = baseUrl.trim(),
        model = model.trim(),
        subAgentModel = subAgentModel.trim(),
        navigatorModel = navigatorModel.trim(),
        maxToolRounds = maxToolRounds.toIntOrNull()?.takeIf { it > 0 } ?: 300,
        maxRepeatedToolCalls = maxRepeatedToolCalls.toIntOrNull()?.takeIf { it > 0 } ?: 20,
        maxNavigationToolCalls = maxNavigationToolCalls.toIntOrNull()?.takeIf { it > 0 } ?: 30,
        maxConsecutiveDelegations = maxConsecutiveDelegations.toIntOrNull()?.takeIf { it > 0 } ?: 3,
        maxContextTokens = maxContextTokens.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_MAX_CONTEXT_TOKENS,
        apiTimeoutSeconds = apiTimeoutSeconds.toLongOrNull()?.takeIf { it >= 0 } ?: DEFAULT_API_TIMEOUT_SECONDS
    )

    /**
     * What storage held when this page last saved, so [dirty] compares the form
     * against it rather than reading storage on every recomposition. Starts from
     * the form's own reading of storage (trimmed, limits defaulted), so a page
     * nobody touched never counts as changed.
     */
    var baseline by remember { mutableStateOf(applyAiConfig(initial)) }
    val dirty = applyAiConfig(baseline) != baseline

    fun commit() {
        onSave { applyAiConfig(it) }
        baseline = applyAiConfig(baseline)
    }

    /** What the Save button needs; the unsaved-changes dialog holds to it too. */
    val canSave = when (provider) {
        LlmProvider.SAMOSA_AI -> samosaToken.isNotBlank() && model.isNotBlank()
        LlmProvider.OPENAI_COMPATIBLE -> baseUrl.isNotBlank() && model.isNotBlank()
    }
    val guardedBack = rememberUnsavedChangesGuard(
        dirty = dirty,
        onSave = ::commit,
        onLeave = onBack,
        canSave = canSave
    )

    /**
     * The settings as they stand on screen, for calls that must see unsaved edits
     * (connection tests, model discovery) rather than what is in storage.
     */
    fun draftAiConfig(): Settings = applyAiConfig(load())

    fun isLocked(m: String) = provider == LlmProvider.SAMOSA_AI && ModelAccess.isLocked(m, allowedModels)

    /**
     * Reads the model list and, for Samosa AI, which of them the plan allows. A
     * chosen model the plan does not allow is swapped for one it does, so Save
     * cannot store a model every chat would then be refused (HTTP 403).
     */
    suspend fun loadModels(announce: Boolean) {
        if (announce) status = localContext.getString(R.string.ai_config_refreshing_models)
        val result = onRefreshChatModels(draftAiConfig())
        result.onSuccess { models ->
            availableChatModels = models
            if (announce) status = localContext.getString(R.string.models_found, models.size)
        }.onFailure { e ->
            if (announce) status = localContext.getString(R.string.models_failed, e.message.orEmpty())
        }
        allowedModels = if (provider == LlmProvider.SAMOSA_AI) onFetchAllowedModels() else null
        if (isLocked(subAgentModel)) subAgentModel = ""
        if (isLocked(navigatorModel)) navigatorModel = ""
        if (isLocked(model)) {
            availableChatModels.firstOrNull { !isLocked(it) }?.let { replacement ->
                status = localContext.getString(R.string.ai_config_model_not_in_plan, model, replacement)
                model = replacement
            }
        }
    }

    val refreshChatModelsAction = {
        if (!refreshingChatModels) {
            refreshingChatModels = true
            scope.launch {
                loadModels(announce = true)
                refreshingChatModels = false
            }
        }
    }

    // Samosa AI's list is checked on opening too, not only when a menu opens:
    // a model saved before the plan changed must be caught before the next chat.
    LaunchedEffect(provider, samosaToken) {
        if (provider == LlmProvider.SAMOSA_AI && samosaToken.isNotBlank() && !refreshingChatModels) {
            refreshingChatModels = true
            loadModels(announce = false)
            refreshingChatModels = false
        } else if (provider != LlmProvider.SAMOSA_AI) {
            allowedModels = null
        }
    }

    SettingsScaffold(title = stringResource(SettingsPage.AI_CONFIG.title), onBack = guardedBack, overlay = overlay) {
        // ---- Provider / model guidance: moot once Samosa AI is the provider ----
        if (provider != LlmProvider.SAMOSA_AI) {
            Text(
                stringResource(R.string.ai_config_recommended_setup_use_the_samosa),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ---- LLM provider selector ----
        ExposedDropdownMenuBox(
            expanded = providerExpanded,
            onExpandedChange = { providerExpanded = it }
        ) {
            OutlinedTextField(
                value = provider.label,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.ai_config_llm_provider)) },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
                    .settingsField("settings_llm_provider")
                    .tourAnchor(TourAnchor.AI_PROVIDER)
            )
            SkinExposedDropdownMenu(
                expanded = providerExpanded,
                onDismissRequest = { providerExpanded = false }
            ) {
                LlmProvider.entries.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.label) },
                        onClick = {
                            provider = p
                            providerExpanded = false
                        }
                    )
                }
            }
        }

        if (provider == LlmProvider.OPENAI_COMPATIBLE && samosaToken.isBlank()) {
            // Someone on their own key: Samosa AI, one tap from the picker above.
            UpsellCard(
                audience = com.gotcha.upsell.UpsellAudience.DISCOVER_SAMOSA,
                reason = com.gotcha.upsell.UpsellReason.GENERAL,
                onOpen = { provider = LlmProvider.SAMOSA_AI },
                actionLabel = R.string.upsell_settings_switch
            )
        }

        if (provider == LlmProvider.SAMOSA_AI) {
            // ---- Samosa AI: Google sign-in (no Base URL / API key) ----
            SamosaAuthSection(
                email = samosaEmail,
                signedIn = samosaToken.isNotBlank(),
                busy = samosaBusy,
                modelsOnly = true,
                signInModifier = Modifier.tourAnchor(TourAnchor.AI_SAMOSA_SIGN_IN),
                onSignIn = {
                    runSamosaSignIn(
                        if (onSamosaSignInWithGoogle != null) {
                            R.string.samosa_opening_sign_in
                        } else {
                            R.string.samosa_signing_in_google
                        },
                        onSamosaSignIn
                    )
                },
                onSignInWithGoogle = onSamosaSignInWithGoogle?.let { google ->
                    {
                        runSamosaSignIn(R.string.samosa_signing_in_google, google)
                    }
                }
            )
        } else {
            // ---- OpenAI-compatible: Base URL + API key (unchanged) ----
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(stringResource(R.string.ai_config_api_key)) },
                singleLine = true,
                visualTransformation = if (showKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(stringResource(if (showKey) R.string.ai_config_hide else R.string.ai_config_show))
                    }
                },
                modifier = Modifier.fillMaxWidth().settingsField("settings_api_key")
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text(stringResource(R.string.ai_config_base_url_openai_compatible)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().settingsField("settings_base_url")
            )
        }
        ExposedDropdownMenuBox(
            expanded = modelExpanded,
            onExpandedChange = {
                modelExpanded = it
                if (it) refreshChatModelsAction()
            }
        ) {
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                // Samosa AI serves a fixed list: pick from it. Typing a name is
                // for OpenAI-compatible servers, whose models Gotcha cannot know.
                readOnly = provider == LlmProvider.SAMOSA_AI,
                label = { Text(stringResource(R.string.ai_config_main_model)) },
                placeholder = { Text(stringResource(R.string.ai_config_select_model)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor().settingsField("settings_model")
            )
            SkinExposedDropdownMenu(
                expanded = modelExpanded,
                onDismissRequest = { modelExpanded = false }
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (refreshingChatModels) R.string.ai_config_refreshing else R.string.ai_config_refresh_models
                            )
                        )
                    },
                    onClick = { refreshChatModelsAction() }
                )
                if (availableChatModels.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ai_config_no_models_found)) },
                        onClick = { modelExpanded = false }
                    )
                } else {
                    availableChatModels.forEach { m ->
                        ModelMenuItem(
                            model = m,
                            locked = isLocked(m),
                            onPick = {
                                model = m
                                modelExpanded = false
                            },
                            onLocked = {
                                modelExpanded = false
                                openUpsell(UpsellReason.GENERAL)
                            }
                        )
                    }
                }
                if (provider != LlmProvider.SAMOSA_AI) {
                    // Closes the menu so the field can be typed in.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ai_config_custom_model)) },
                        onClick = {
                            modelExpanded = false
                        }
                    )
                }
            }
        }
        // ---- Advanced: model overrides, agent-loop limits, maintenance ----
        // Collapsed by default. The fields' state lives at the top of this
        // composable and `applyAiConfig` reads it either way, so folding the
        // section away never drops an edit or changes what Save writes.
        SettingsAdvancedSection(testTag = AI_ADVANCED_SECTION) {
            ExposedDropdownMenuBox(
                expanded = subAgentModelExpanded,
                onExpandedChange = {
                    subAgentModelExpanded = it
                    if (it) refreshChatModelsAction()
                }
            ) {
                val subLabel = subAgentModel.ifBlank { stringResource(R.string.ai_config_same_as_main_agent) }
                OutlinedTextField(
                    value = subLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.ai_config_sub_agent_model)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(
                            expanded = subAgentModelExpanded
                        )
                    },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                SkinExposedDropdownMenu(
                    expanded = subAgentModelExpanded,
                    onDismissRequest = { subAgentModelExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (refreshingChatModels) R.string.ai_config_refreshing else R.string.ai_config_refresh_models
                                )
                            )
                        },
                        onClick = { refreshChatModelsAction() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ai_config_same_as_main_agent)) },
                        onClick = {
                            subAgentModel = ""
                            subAgentModelExpanded = false
                        }
                    )
                    if (availableChatModels.isNotEmpty()) {
                        availableChatModels.forEach { m ->
                            ModelMenuItem(
                                model = m,
                                locked = isLocked(m),
                                onPick = {
                                    subAgentModel = m
                                    subAgentModelExpanded = false
                                },
                                onLocked = {
                                    subAgentModelExpanded = false
                                    openUpsell(UpsellReason.GENERAL)
                                }
                            )
                        }
                    }
                }
            }
            ExposedDropdownMenuBox(
                expanded = navigatorModelExpanded,
                onExpandedChange = {
                    navigatorModelExpanded = it
                    if (it) refreshChatModelsAction()
                }
            ) {
                val navLabel = navigatorModel.ifBlank { stringResource(R.string.ai_config_same_as_main_model) }
                OutlinedTextField(
                    value = navLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.ai_config_navigator_model)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(
                            expanded = navigatorModelExpanded
                        )
                    },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                SkinExposedDropdownMenu(
                    expanded = navigatorModelExpanded,
                    onDismissRequest = { navigatorModelExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (refreshingChatModels) R.string.ai_config_refreshing else R.string.ai_config_refresh_models
                                )
                            )
                        },
                        onClick = { refreshChatModelsAction() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ai_config_same_as_main_model)) },
                        onClick = {
                            navigatorModel = ""
                            navigatorModelExpanded = false
                        }
                    )
                    if (availableChatModels.isNotEmpty()) {
                        availableChatModels.forEach { m ->
                            ModelMenuItem(
                                model = m,
                                locked = isLocked(m),
                                onPick = {
                                    navigatorModel = m
                                    navigatorModelExpanded = false
                                },
                                onLocked = {
                                    navigatorModelExpanded = false
                                    openUpsell(UpsellReason.GENERAL)
                                }
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = maxToolRounds,
                onValueChange = { maxToolRounds = it },
                label = { Text(stringResource(R.string.ai_config_max_tool_rounds)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().settingsField("settings_max_tool_rounds")
            )
            OutlinedTextField(
                value = maxRepeatedToolCalls,
                onValueChange = { maxRepeatedToolCalls = it },
                label = { Text(stringResource(R.string.ai_config_max_repeated_tool_calls)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = maxNavigationToolCalls,
                onValueChange = { maxNavigationToolCalls = it },
                label = { Text(stringResource(R.string.ai_config_max_navigation_tool_calls)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = maxConsecutiveDelegations,
                onValueChange = { maxConsecutiveDelegations = it },
                label = { Text(stringResource(R.string.ai_config_max_consecutive_delegations)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = maxContextTokens,
                onValueChange = { maxContextTokens = it },
                label = { Text(stringResource(R.string.ai_config_max_context_tokens)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = apiTimeoutSeconds,
                onValueChange = { apiTimeoutSeconds = it },
                label = { Text(stringResource(R.string.ai_config_api_timeout_seconds)) },
                supportingText = {
                    Text(stringResource(R.string.ai_config_api_timeout_hint, DEFAULT_API_TIMEOUT_SECONDS))
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().settingsField("settings_api_timeout")
            )
        }
        Button(
            onClick = {
                commit()
                Telemetry.track(
                    FeatureEvents.setup(
                        SetupAction.SAVED,
                        provider,
                        baseUrl.trim(),
                        hasKey = when (provider) {
                            LlmProvider.SAMOSA_AI -> samosaToken.isNotBlank()
                            LlmProvider.OPENAI_COMPATIBLE -> apiKey.isNotBlank()
                        }
                    )
                )
                overlay.show(localContext.getString(R.string.settings_saved))
                status = null
            },
            enabled = canSave,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_save")
                .tourAnchor(TourAnchor.AI_SAVE)
        ) { Text(stringResource(R.string.ai_config_save)) }
        OutlinedButton(
            onClick = {
                testing = true
                // Sticky while the request is in flight, then the result
                // replaces it and fades out on its own.
                overlay.show(localContext.getString(R.string.ai_config_testing_connection), sticky = true)
                status = null
                scope.launch {
                    val draft = draftAiConfig()
                    val result = onTestConnection(draft)
                    Telemetry.track(FeatureEvents.setup(draft, SetupAction.TESTED, testOk = result.isSuccess))
                    overlay.show(
                        result.fold(
                            onSuccess = { localContext.getString(R.string.ai_config_connected, it) },
                            onFailure = {
                                localContext.getString(R.string.ai_config_connection_failed, it.message.orEmpty())
                            }
                        )
                    )
                    testing = false
                }
            },
            enabled = !testing && when (provider) {
                LlmProvider.SAMOSA_AI -> samosaToken.isNotBlank()
                LlmProvider.OPENAI_COMPATIBLE -> baseUrl.isNotBlank()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.ai_config_test_connection)) }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }

        // About the key and base URL fields, which only an OpenAI-compatible provider shows.
        if (provider != LlmProvider.SAMOSA_AI) {
            Text(
                stringResource(R.string.ai_config_the_api_key_is_stored),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * One model in a model menu. A model outside the user's Samosa AI plan keeps
 * its place in the list, dimmed with a lock, and opens the Pro offer instead of
 * being chosen.
 */
@Composable
internal fun ModelMenuItem(
    model: String,
    locked: Boolean,
    onPick: () -> Unit,
    onLocked: () -> Unit
) {
    val lockedLabel = stringResource(R.string.ai_config_model_locked)
    DropdownMenuItem(
        text = {
            Text(
                model,
                color = if (locked) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
            )
        },
        trailingIcon = if (locked) {
            { Icon(Icons.Outlined.Lock, contentDescription = lockedLabel) }
        } else {
            null
        },
        onClick = if (locked) onLocked else onPick,
        modifier = if (locked) Modifier.semantics { stateDescription = lockedLabel } else Modifier
    )
}
