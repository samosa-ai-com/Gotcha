package com.gotcha.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gotcha.R
import com.gotcha.data.Settings
import com.gotcha.ui.theme.SkinExposedDropdownMenu
import com.gotcha.ui.tour.TourAnchor
import com.gotcha.ui.tour.tourAnchor

/** Currencies offered for [Settings.preferredCurrency]. */
private val CURRENCIES = listOf("USD", "EUR", "GBP", "INR", "CAD", "AUD", "JPY", "CNY")

/**
 * The Personal Info page: who the user is and how they want to be answered.
 *
 * Every field here reaches the model's system prompt — the facts as a
 * `<user_profile>` block, the reply-style text as a directive next to the
 * language one (see `AgentEngine`). Nothing on this page changes what the agent
 * is *allowed* to do; it only changes what it knows about the person asking.
 *
 * Currency lives here rather than under Proactive Assistance, where it started:
 * it describes the user, not whether the assistant volunteers help, and it
 * applies to every reply whether proactive or not. The language settings used to
 * sit here too and now have their own page (issue #74) — "Preferred Language"
 * next to a name and an occupation read as the app's UI language, which it never
 * was; this page keeps only a pointer to them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalInfoScreen(
    load: () -> Settings,
    onSave: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit,
    onOpenLanguage: () -> Unit = {}
) {
    val initial = remember { load() }
    var userName by remember { mutableStateOf(initial.userName) }
    var userLocation by remember { mutableStateOf(initial.userLocation) }
    var userOccupation by remember { mutableStateOf(initial.userOccupation) }
    var userBackground by remember { mutableStateOf(initial.userBackground) }
    var userResponseStyle by remember { mutableStateOf(initial.userResponseStyle) }
    var preferredCurrency by remember { mutableStateOf(initial.preferredCurrency) }

    var currencyExpanded by remember { mutableStateOf(false) }

    val overlay = rememberSettingsOverlayState()

    /** This page's fields, copied onto [base]. */
    fun applyPersonalInfo(base: Settings) = base.copy(
        userName = userName.trim(),
        userLocation = userLocation.trim(),
        userOccupation = userOccupation.trim(),
        userBackground = userBackground.trim(),
        userResponseStyle = userResponseStyle.trim(),
        preferredCurrency = preferredCurrency
    )

    SettingsScaffold(title = stringResource(SettingsPage.PERSONAL_INFO.title), onBack = onBack, overlay = overlay) {
        Text(
            stringResource(R.string.personal_info_about_you),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            stringResource(R.string.personal_info_everything_here_is_optional_stays),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            stringResource(R.string.personal_info_for_more_personalized_results_keep),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = userName,
            onValueChange = { userName = it },
            label = { Text(stringResource(R.string.personal_info_name)) },
            placeholder = { Text(stringResource(R.string.personal_info_what_the_assistant_should_call)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .settingsField("settings_user_name")
                .tourAnchor(TourAnchor.PERSONAL_NAME)
        )
        OutlinedTextField(
            value = userLocation,
            onValueChange = { userLocation = it },
            label = { Text(stringResource(R.string.personal_info_location)) },
            placeholder = { Text(stringResource(R.string.personal_info_e_g_munich_germany)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_user_location")
        )
        OutlinedTextField(
            value = userOccupation,
            onValueChange = { userOccupation = it },
            label = { Text(stringResource(R.string.personal_info_occupation)) },
            placeholder = { Text(stringResource(R.string.personal_info_e_g_backend_engineer)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_user_occupation")
        )
        OutlinedTextField(
            value = userBackground,
            onValueChange = { userBackground = it },
            label = { Text(stringResource(R.string.personal_info_background)) },
            placeholder = {
                Text(
                    stringResource(R.string.personal_info_anything_worth_knowing_by_default)
                )
            },
            minLines = 3,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_user_background")
        )

        HorizontalDivider(thickness = 1.dp)

        // ---- Output preferences ----
        Text(
            stringResource(R.string.personal_info_how_replies_should_be_written),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        OutlinedTextField(
            value = userResponseStyle,
            onValueChange = { userResponseStyle = it },
            label = { Text(stringResource(R.string.personal_info_reply_style)) },
            placeholder = {
                Text(
                    stringResource(R.string.personal_info_e_g_keep_it_to)
                )
            },
            minLines = 3,
            modifier = Modifier
                .fillMaxWidth()
                .settingsField("settings_user_response_style")
        )

        ExposedDropdownMenuBox(
            expanded = currencyExpanded,
            onExpandedChange = { currencyExpanded = it }
        ) {
            OutlinedTextField(
                value = preferredCurrency,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.personal_info_preferred_currency)) },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = currencyExpanded)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
                    .settingsField("settings_user_currency")
            )
            SkinExposedDropdownMenu(
                expanded = currencyExpanded,
                onDismissRequest = { currencyExpanded = false }
            ) {
                CURRENCIES.forEach { curr ->
                    DropdownMenuItem(
                        text = { Text(curr) },
                        onClick = {
                            preferredCurrency = curr
                            currencyExpanded = false
                        }
                    )
                }
            }
        }

        HorizontalDivider(thickness = 1.dp)

        // Language moved out of this page (issue #74); a pointer keeps it findable
        // for anyone who still comes here looking for "Preferred Language".
        Text(
            stringResource(R.string.personal_info_reply_voice_and_app_display),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SettingsNavRow(
            page = SettingsPage.LANGUAGE,
            onClick = onOpenLanguage,
            modifier = Modifier.testTag("settings_personal_info_language_row")
        )

        val saved = stringResource(R.string.personal_info_saved)
        Button(
            onClick = {
                onSave { applyPersonalInfo(it) }
                overlay.show(saved)
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_save_personal_info")
                .tourAnchor(TourAnchor.PERSONAL_SAVE)
        ) { Text(stringResource(R.string.personal_info_save_personal_info)) }
    }
}
