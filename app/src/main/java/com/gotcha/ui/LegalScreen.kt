package com.gotcha.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.gotcha.data.LEGAL_VERSION
import com.gotcha.data.Settings

/**
 * The Legal page: shows the Terms, Disclaimer, and Data Retention documents
 * and lets the user re-affirm acceptance from here.
 *
 * The three documents are loaded once from `assets/legal/{terms,disclaimer,
 * privacy-data-retention}.md` and rendered with a small, dependency-free
 * Markdown pass (headings, blockquotes, bold). An "I agree" button writes
 * [Settings.legalAcceptedVersion] so the user never sees the first-launch
 * gate again unless the documents change.
 */
@Composable
fun LegalScreen(
    context: Context,
    load: () -> Settings,
    onSave: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit
) {
    val overlay = rememberSettingsOverlayState()
    val initial = remember { load() }

    var termsText by remember { mutableStateOf<String?>(null) }
    var disclaimerText by remember { mutableStateOf<String?>(null) }
    var privacyText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        termsText = readAsset(context, "legal/terms.md")
        disclaimerText = readAsset(context, "legal/disclaimer.md")
        privacyText = readAsset(context, "legal/privacy-data-retention.md")
    }

    SettingsScaffold(title = SettingsPage.LEGAL.title, onBack = onBack, overlay = overlay) {
        Text(
            "By using Gotcha you accept these documents.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LegalSection(label = "Terms and Conditions", markdown = termsText)
        LegalSection(label = "Disclaimer and Declaration", markdown = disclaimerText)
        LegalSection(label = "Data Retention and Privacy Policy", markdown = privacyText)

        HorizontalDivider(thickness = 1.dp)

        AcceptBlock(
            acceptedVersion = initial.legalAcceptedVersion,
            onAccept = {
                onSave { it.copy(legalAcceptedVersion = LEGAL_VERSION) }
                overlay.show("Accepted. You won't see this gate again until the legal copy changes.")
            }
        )
    }
}

@Composable
private fun LegalSection(label: String, markdown: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            val body = markdown ?: "(loading…)"
            Text(text = renderLegalMarkdown(body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ColumnScope.AcceptBlock(
    acceptedVersion: String,
    onAccept: () -> Unit
) {
    val currentVersion = LEGAL_VERSION
    if (acceptedVersion == currentVersion) {
        Text(
            text = "You've accepted version $currentVersion. You'll be asked again " +
                "only if the legal copy changes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start
        )
        return
    }
    OutlinedButton(
        onClick = onAccept,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_legal_accept_button")
    ) { Text("I agree") }
    Text(
        text = if (acceptedVersion.isBlank()) {
            "You haven't accepted yet. The first-launch dialog will keep showing " +
                "until you tap I agree."
        } else {
            "You accepted an earlier version ($acceptedVersion). The current " +
                "version is $currentVersion."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Shared with [AboutScreen], which renders the bundled company copy the same way. */
internal fun readAsset(context: Context, path: String): String = try {
    context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
} catch (t: Throwable) {
    "Failed to read asset $path: ${t.message ?: t::class.java.simpleName}"
}

/**
 * Tiny, dependency-free Markdown pass for the features the legal documents
 * actually use: `#`/`##`/`###` headings, `> ` blockquotes, blank-line
 * paragraph breaks. The legal copy is hand-written and only uses these
 * primitives, so this is enough for readability without a library.
 *
 * Numbered and bulleted list items are left as inline text — the legal
 * documents number sections manually ("1.1", "1.2", …), which would be
 * mangled by a naive list pass, and the current rendered form is fine.
 */
internal fun renderLegalMarkdown(src: String): AnnotatedString = buildAnnotatedString {
    for (line in src.lines()) {
        when {
            line.startsWith("# ") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(line.removePrefix("# "))
                }
                append('\n')
            }
            line.startsWith("## ") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    append(line.removePrefix("## "))
                }
                append('\n')
            }
            line.startsWith("### ") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                    append(line.removePrefix("### "))
                }
                append('\n')
            }
            line.startsWith("> ") -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(line.removePrefix("> "))
                }
                append('\n')
            }
            line.isBlank() -> append('\n')
            else -> {
                append(line)
                append('\n')
            }
        }
    }
}
