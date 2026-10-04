package com.gotcha.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.gotcha.R
import com.gotcha.data.DEFAULT_API_TIMEOUT_SECONDS
import com.gotcha.data.DEFAULT_MAX_CONTEXT_TOKENS
import com.gotcha.data.LlmProvider
import com.gotcha.data.Settings
import com.gotcha.ui.theme.SkinExposedDropdownMenu
import com.gotcha.ui.tour.TourAnchor
import com.gotcha.ui.tour.tourAnchor
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
    onSamosaSignIn: suspend () -> Result<Pair<String, String>> = {
        Result.failure(Exception("Not available"))
    },
    onSamosaSignOut: suspend () -> Unit = {},
    /** Fetches the user's full profile (including tier, tags, referral) or null when unavailable. */
    onFetchSamosaProfile: suspend () -> com.gotcha.auth.SamosaUser? = { null },
    /** Claims an invite code via the auth manager. */
    onClaimReferral: suspend (String) -> Result<Unit> = {
        Result.failure(Exception("Not supported"))
    },
    onClearLlmCache: () -> Unit = {},
    onClearDebugScreenshots: () -> Unit = {}
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
    var samosaCredits by remember { mutableStateOf<Double?>(null) }
    var samosaUser by remember { mutableStateOf<com.gotcha.auth.SamosaUser?>(null) }
    var referralBusy by remember { mutableStateOf(false) }
    var referralError by remember { mutableStateOf<String?>(null) }
    var subAgentModel by remember { mutableStateOf(initial.subAgentModel) }
    var navigatorModel by remember { mutableStateOf(initial.navigatorModel) }
    var maxToolRounds by remember { mutableStateOf(initial.maxToolRounds.toString()) }
    var maxRepeatedToolCalls by remember { mutableStateOf(initial.maxRepeatedToolCalls.toString()) }
    var maxNavigationToolCalls by remember { mutableStateOf(initial.maxNavigationToolCalls.toString()) }
    var maxConsecutiveDelegations by remember { mutableStateOf(initial.maxConsecutiveDelegations.toString()) }
    var maxContextTokens by remember { mutableStateOf(initial.maxContextTokens.toString()) }
    var apiTimeoutSeconds by remember { mutableStateOf(initial.apiTimeoutSeconds.toString()) }

    var availableChatModels by remember { mutableStateOf<List<String>>(emptyList()) }
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

    // Fetch the profile & credit balance when signed in, and whenever the token changes
    // (sign-in sets it, sign-out clears it). Keep it light: no polling.
    LaunchedEffect(samosaToken) {
        if (samosaToken.isBlank()) {
            samosaCredits = null
            samosaUser = null
        } else {
            val profile = onFetchSamosaProfile()
            samosaUser = profile
            samosaCredits = profile?.creditsRemaining
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
     * The settings as they stand on screen, for calls that must see unsaved edits
     * (connection tests, model discovery) rather than what is in storage.
     */
    fun draftAiConfig(): Settings = applyAiConfig(load())

    val refreshChatModelsAction = {
        if (!refreshingChatModels) {
            refreshingChatModels = true
            status = localContext.getString(R.string.ai_config_refreshing_models)
            scope.launch {
                val result = onRefreshChatModels(draftAiConfig())
                result.onSuccess { models ->
                    availableChatModels = models
                    status = localContext.getString(R.string.models_found, models.size)
                }.onFailure { e ->
                    status = localContext.getString(R.string.models_failed, e.message.orEmpty())
                }
                refreshingChatModels = false
            }
        }
    }

    SettingsScaffold(title = stringResource(SettingsPage.AI_CONFIG.title), onBack = onBack, overlay = overlay) {
        // ---- Provider / model guidance ----
        Text(
            stringResource(R.string.ai_config_recommended_setup_use_the_samosa),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

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

        if (provider == LlmProvider.SAMOSA_AI) {
            // ---- Samosa AI: Google sign-in (no Base URL / API key) ----
            SamosaAuthSection(
                email = samosaEmail,
                signedIn = samosaToken.isNotBlank(),
                busy = samosaBusy,
                creditsRemaining = samosaCredits,
                user = samosaUser,
                referralBusy = referralBusy,
                referralError = referralError,
                onClaimReferral = { code ->
                    referralBusy = true
                    referralError = null
                    scope.launch {
                        val res = onClaimReferral(code)
                        res.onSuccess {
                            val profile = onFetchSamosaProfile()
                            samosaUser = (profile ?: samosaUser)?.let { u ->
                                u.copy(
                                    referral = u.referral.copy(canClaim = false),
                                    creditsRemaining = profile?.creditsRemaining ?: samosaCredits
                                )
                            }
                            samosaCredits = samosaUser?.creditsRemaining
                            referralBusy = false
                            status = localContext.getString(R.string.samosa_invite_applied)
                        }.onFailure { e ->
                            referralError = e.message ?: localContext.getString(R.string.samosa_invite_failed)
                            referralBusy = false
                        }
                    }
                },
                signInModifier = Modifier.tourAnchor(TourAnchor.AI_SAMOSA_SIGN_IN),
                onSignIn = {
                    samosaBusy = true
                    status = localContext.getString(R.string.samosa_signing_in_google)
                    scope.launch {
                        val result = onSamosaSignIn()
                        result.onSuccess { (email, token) ->
                            samosaEmail = email
                            samosaToken = token
                            val profile = onFetchSamosaProfile()
                            samosaUser = profile
                            samosaCredits = profile?.creditsRemaining
                            status = localContext.getString(R.string.samosa_signed_in_as, email)
                        }.onFailure { e ->
                            status = e.message ?: localContext.getString(R.string.samosa_sign_in_failed)
                        }
                        samosaBusy = false
                    }
                },
                onSignOut = {
                    samosaBusy = true
                    status = localContext.getString(R.string.samosa_signing_out)
                    scope.launch {
                        onSamosaSignOut()
                        samosaToken = ""
                        samosaEmail = ""
                        samosaCredits = null
                        samosaUser = null
                        availableChatModels = emptyList()
                        status = localContext.getString(R.string.samosa_signed_out)
                        samosaBusy = false
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
                readOnly = false,
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
                        DropdownMenuItem(
                            text = { Text(m) },
                            onClick = {
                                model = m
                                modelExpanded = false
                            }
                        )
                    }
                }
                // Always allow manual text input
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ai_config_custom_model)) },
                    onClick = {
                        modelExpanded = false
                    }
                )
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
                            DropdownMenuItem(
                                text = { Text(m) },
                                onClick = {
                                    subAgentModel = m
                                    subAgentModelExpanded = false
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
                            DropdownMenuItem(
                                text = { Text(m) },
                                onClick = {
                                    navigatorModel = m
                                    navigatorModelExpanded = false
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
            OutlinedButton(
                onClick = {
                    onClearLlmCache()
                    overlay.show(localContext.getString(R.string.ai_config_llm_response_cache_cleared))
                    status = null
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.ai_config_clear_llm_cache)) }
            OutlinedButton(
                onClick = {
                    onClearDebugScreenshots()
                    overlay.show(localContext.getString(R.string.ai_config_debug_screenshots_cleared))
                    status = null
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.ai_config_clear_debug_screenshots)) }
        }
        Button(
            onClick = {
                onSave { applyAiConfig(it) }
                overlay.show(localContext.getString(R.string.settings_saved))
                status = null
            },
            enabled = when (provider) {
                LlmProvider.SAMOSA_AI -> samosaToken.isNotBlank() && model.isNotBlank()
                LlmProvider.OPENAI_COMPATIBLE -> baseUrl.isNotBlank() && model.isNotBlank()
            },
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
                    val result = onTestConnection(draftAiConfig())
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

        Text(
            stringResource(R.string.ai_config_the_api_key_is_stored),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
