package com.gotcha.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gotcha.ui.theme.SkinAlertDialog

/**
 * 1-step onboarding dialog shown immediately after Google Sign-In for new users.
 * Allows entering/applying a friend's referral code or skipping.
 */
@Composable
fun ReferralInviteDialog(
    initialCode: String? = null,
    busy: Boolean = false,
    errorMessage: String? = null,
    onApply: (String) -> Unit,
    onSkip: () -> Unit
) {
    var codeText by remember(initialCode) { mutableStateOf(initialCode ?: "") }

    SkinAlertDialog(
        onDismissRequest = { if (!busy) onSkip() },
        title = {
            Text(
                text = "🎁 Have an Invite Code?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Enter a friend's code to earn bonus credits.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = codeText,
                    onValueChange = { codeText = it.uppercase().trim() },
                    label = { Text("Invite Code (e.g. AIR-K9X2P7)") },
                    placeholder = { Text("AIR-XXXXXX") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )

                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onApply(codeText) },
                enabled = !busy && codeText.isNotBlank()
            ) {
                if (busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Text("Applying…")
                    }
                } else {
                    Text("Apply Code")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onSkip,
                enabled = !busy
            ) {
                Text("Skip")
            }
        }
    )
}
