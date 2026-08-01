package com.gotcha

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gotcha.ui.renderLegalMarkdown
import com.gotcha.ui.theme.LocalSkin

/**
 * First-launch / re-acceptance gate. Non-dismissable: the only way out is the
 * "I agree" button, which is itself disabled until the user toggles the
 * "I have read and accept…" checkbox. When that toggle is on, the dialog
 * expands to show all three legal documents inline (Terms, Disclaimer, Data
 * Retention) so the user knows exactly what they are agreeing to without
 * leaving the dialog.
 *
 * Re-prompted by [com.gotcha.MainActivity] whenever the stored
 * [com.gotcha.data.Settings.legalAcceptedVersion] doesn't match the current
 * [com.gotcha.data.LEGAL_VERSION], which is the e-tag-style mechanism the
 * documents ship under.
 */
@Composable
fun LegalConsentDialog(
    onAgree: () -> Unit
) {
    val context = LocalContext.current
    var showFull by remember { mutableStateOf(false) }
    var termsText by remember { mutableStateOf<String?>(null) }
    var disclaimerText by remember { mutableStateOf<String?>(null) }
    var privacyText by remember { mutableStateOf<String?>(null) }

    // Lazy load the three legal documents on first toggle. Reading them from
    // assets is fast (a few KB each), but doing it eagerly would block the
    // dialog's first frame for no benefit when the user never toggles.
    LaunchedEffect(showFull) {
        if (showFull && termsText == null) {
            termsText = readAsset(context, "legal/terms.md")
            disclaimerText = readAsset(context, "legal/disclaimer.md")
            privacyText = readAsset(context, "legal/privacy-data-retention.md")
        }
    }

    val bullets = stringArrayResource(R.array.legal_consent_bullets)
    val loadingLabel = stringResource(R.string.legal_consent_loading)

    AlertDialog(
        onDismissRequest = { /* non-dismissable until accepted */ },
        // Material 3 paints a dialog from `surface`, which every glass skin
        // leaves see-through for in-page chrome — so the chat screen reads
        // straight through the legal copy. Same colour with the ground already
        // behind it, exactly as the long-press menus do. See Skin.menuContainer.
        containerColor = LocalSkin.current.menuContainer,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.legal_consent_title),
                    fontWeight = FontWeight.Bold
                )
                // The body scrolls, and without a line to scroll under, a
                // half-cut sentence sitting against the title reads as a
                // rendering fault rather than as more text below.
                HorizontalDivider(thickness = 1.dp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.legal_consent_intro),
                    style = MaterialTheme.typography.bodyMedium
                )

                bullets.forEach { bullet ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = bullet,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                HorizontalDivider(thickness = 1.dp)

                // The toggle row. Whole row is clickable so the user can hit
                // anywhere on the label, not just the small checkbox target.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = showFull,
                            role = Role.Checkbox,
                            onValueChange = { showFull = it }
                        )
                        .padding(vertical = 4.dp)
                        .testTag("legal_consent_toggle_row"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = showFull,
                        onCheckedChange = null,
                        modifier = Modifier.testTag("legal_consent_toggle_checkbox")
                    )
                    Text(
                        text = stringResource(R.string.legal_consent_toggle_label),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (showFull) {
                    HorizontalDivider(thickness = 1.dp)

                    DocumentBlock(
                        title = stringResource(R.string.legal_consent_section_terms),
                        body = termsText,
                        loadingLabel = loadingLabel
                    )
                    DocumentBlock(
                        title = stringResource(R.string.legal_consent_section_disclaimer),
                        body = disclaimerText,
                        loadingLabel = loadingLabel
                    )
                    DocumentBlock(
                        title = stringResource(R.string.legal_consent_section_privacy),
                        body = privacyText,
                        loadingLabel = loadingLabel
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onAgree,
                enabled = showFull,
                modifier = Modifier.testTag("legal_consent_agree_button")
            ) { Text(stringResource(R.string.legal_consent_agree)) }
        }
        // No dismissButton — the dialog has no "later" path on purpose.
    )
}

/**
 * One legal document inline: section title plus its rendered Markdown body.
 * Sits in a soft surface card so the eye picks it out from the surrounding
 * summary text, which uses the default background.
 */
@Composable
private fun DocumentBlock(title: String, body: String?, loadingLabel: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = renderLegalMarkdown(body ?: loadingLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Read a UTF-8 text asset; never throws — a missing asset shows an error string. */
private fun readAsset(context: Context, path: String): String = try {
    context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
} catch (t: Throwable) {
    "Failed to read asset $path: ${t.message ?: t::class.java.simpleName}"
}
