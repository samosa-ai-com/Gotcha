package com.gotcha.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gotcha.notifications.NotificationPayload

@Composable
fun NotificationDetailDialog(
    payload: NotificationPayload,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = payload.title,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 350.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = payload.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            val hasValidUrl = !payload.url.isNullOrBlank() && payload.url.startsWith("https://")
            if (hasValidUrl) {
                Button(
                    onClick = {
                        onDismiss()
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(payload.url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    }
                ) {
                    Text("Open Link")
                }
            } else {
                Button(onClick = onDismiss) {
                    Text("OK")
                }
            }
        },
        dismissButton = {
            val hasValidUrl = !payload.url.isNullOrBlank() && payload.url.startsWith("https://")
            if (hasValidUrl) {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    )
}
