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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
        maxContextTokens = maxContextTokens.toIntOrNull()?.takeIf { it > 0 } ?: 70000,
        apiTimeoutSeconds = apiTimeoutSeconds.toLongOrNull()?.takeIf { it >= 0 } ?: 0L
    )

    /**
     * The settings as they stand on screen, for calls that must see unsaved edits
     * (connection tests, model discovery) rather than what is in storage.
     */
    fun draftAiConfig(): Settings = applyAiConfig(load())

    val refreshChatModelsAction = {
        if (!refreshingChatModels) {
            refreshingChatModels = true
            status = "Refreshing models…"
            scope.launch {
                val result = onRefreshChatModels(draftAiConfig())
                result.onSuccess { models ->
                    availableChatModels = models
                    status = "Found ${models.size} models"
                }.onFailure { e ->
                    status = "Failed: ${e.message}"
                }
                refreshingChatModels = false
            }
        }
    }

    SettingsScaffold(title = SettingsPage.AI_CONFIG.title, onBack = onBack, overlay = overlay) {
        // ---- Provider / model guidance ----
        Text(
            "Recommended setup: Use the SAMOSA AI provider for the best LLM performance.\n",
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
                label = { Text("LLM Provider") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
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
                            status = "Invite code applied!"
                        }.onFailure { e ->
                            referralError = e.message ?: "Failed to apply invite code."
                            referralBusy = false
                        }
                    }
                },
                signInModifier = Modifier.tourAnchor(TourAnchor.AI_SAMOSA_SIGN_IN),
                onSignIn = {
                    samosaBusy = true
                    status = "Signing in with Google…"
                    scope.launch {
                        val result = onSamosaSignIn()
                        result.onSuccess { (email, token) ->
                            samosaEmail = email
                            samosaToken = token
                            val profile = onFetchSamosaProfile()
                            samosaUser = profile
                            samosaCredits = profile?.creditsRemaining
                            status = "Signed in as $email"
                        }.onFailure { e ->
                            status = e.message ?: "Sign-in failed."
                        }
                        samosaBusy = false
                    }
                },
                onSignOut = {
                    samosaBusy = true
                    status = "Signing out…"
                    scope.launch {
                        onSamosaSignOut()
                        samosaToken = ""
                        samosaEmail = ""
                        samosaCredits = null
                        samosaUser = null
                        availableChatModels = emptyList()
                        status = "Signed out of Samosa AI."
                        samosaBusy = false
                    }
                }
            )
        } else {
            // ---- OpenAI-compatible: Base URL + API key (unchanged) ----
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = if (showKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("settings_api_key")
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL (OpenAI-compatible)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("settings_base_url")
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
                label = { Text("Main model") },
                placeholder = { Text("(select model)") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor().testTag("settings_model")
            )
            SkinExposedDropdownMenu(
                expanded = modelExpanded,
                onDismissRequest = { modelExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(if (refreshingChatModels) "Refreshing…" else "🔄 Refresh models…") },
                    onClick = { refreshChatModelsAction() }
                )
                if (availableChatModels.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No models found") },
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
                    text = { Text("✏️ Custom model…") },
                    onClick = {
                        modelExpanded = false
                    }
                )
            }
        }
        ExposedDropdownMenuBox(
            expanded = subAgentModelExpanded,
            onExpandedChange = {
                subAgentModelExpanded = it
                if (it) refreshChatModelsAction()
            }
        ) {
            val subLabel = if (subAgentModel.isBlank()) "Same as main agent" else subAgentModel
            OutlinedTextField(
                value = subLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text("Sub-agent model") },
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
                    text = { Text(if (refreshingChatModels) "Refreshing…" else "🔄 Refresh models…") },
                    onClick = { refreshChatModelsAction() }
                )
                DropdownMenuItem(
                    text = { Text("Same as main agent") },
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
            val navLabel = if (navigatorModel.isBlank()) "Same as main model" else navigatorModel
            OutlinedTextField(
                value = navLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text("Navigator model") },
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
                    text = { Text(if (refreshingChatModels) "Refreshing…" else "🔄 Refresh models…") },
                    onClick = { refreshChatModelsAction() }
                )
                DropdownMenuItem(
                    text = { Text("Same as main model") },
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
            label = { Text("Max tool rounds") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = maxRepeatedToolCalls,
            onValueChange = { maxRepeatedToolCalls = it },
            label = { Text("Max repeated tool calls") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = maxNavigationToolCalls,
            onValueChange = { maxNavigationToolCalls = it },
            label = { Text("Max navigation tool calls") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = maxConsecutiveDelegations,
            onValueChange = { maxConsecutiveDelegations = it },
            label = { Text("Max consecutive delegations") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = maxContextTokens,
            onValueChange = { maxContextTokens = it },
            label = { Text("Max context tokens") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = apiTimeoutSeconds,
            onValueChange = { apiTimeoutSeconds = it },
            label = { Text("API Timeout (seconds, 0 for infinite)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                onSave { applyAiConfig(it) }
                overlay.show("Saved.")
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
        ) { Text("Save") }
        OutlinedButton(
            onClick = {
                testing = true
                // Sticky while the request is in flight, then the result
                // replaces it and fades out on its own.
                overlay.show("Testing connection…", sticky = true)
                status = null
                scope.launch {
                    val result = onTestConnection(draftAiConfig())
                    overlay.show(
                        result.fold(
                            onSuccess = { "✓ Connected: $it" },
                            onFailure = { "✗ Connection failed: ${it.message}" }
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
        ) { Text("Test connection") }
        OutlinedButton(
            onClick = {
                onClearLlmCache()
                overlay.show("LLM response cache cleared.")
                status = null
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Clear LLM cache") }
        OutlinedButton(
            onClick = {
                onClearDebugScreenshots()
                overlay.show("Debug screenshots cleared.")
                status = null
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Clear debug screenshots") }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }

        Text(
            "The API key is stored encrypted on this device and never leaves it " +
                "except in requests to the base URL above.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
