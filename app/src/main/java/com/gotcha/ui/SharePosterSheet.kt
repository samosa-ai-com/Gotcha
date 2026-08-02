package com.gotcha.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gotcha.data.RunSummary

/**
 * Share flow for the "Share your Gotcha moment" feature.
 *
 * Phase 1 (config): an include-screenshot toggle + Generate button.
 * Phase 2 (preview): the rendered poster bitmap with Share / Save / Regenerate.
 *
 * [runs] are the raw run summaries this card is built from; the caller supplies
 * [onGenerate] (one LLM call + render) and the share/save/regenerate handlers.
 */
@Composable
fun SharePosterSheet(
    runs: List<RunSummary>,
    loading: Boolean,
    preview: Bitmap?,
    error: String?,
    hasImage: Boolean,
    onGenerate: (includeScreenshot: Boolean) -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onRegenerate: (includeScreenshot: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var includeScreenshot by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = {
            Text(
                if (runs.size == 1) "Share this moment" else "Share your ${runs.size} moments"
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Turn what Gotcha just did into a shareable Instagram poster.",
                    style = MaterialTheme.typography.bodyMedium
                )

                // The screenshot toggle only makes sense when the chat history
                // actually holds an image the poster can embed; hide it otherwise
                // (e.g. the image was culled from the session).
                if (hasImage) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Include a screenshot", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = includeScreenshot,
                            onCheckedChange = { includeScreenshot = it },
                            enabled = !loading
                        )
                    }
                }

                when {
                    loading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Writing your poster…",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    preview != null -> {
                        Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = "Your share poster",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(340.dp),
                            contentScale = ContentScale.Fit
                        )
                        error?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = onShare, modifier = Modifier.weight(1f)) {
                                Text("Share")
                            }
                            Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                                Text("Save")
                            }
                        }
                        TextButton(
                            onClick = { onRegenerate(includeScreenshot) },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            enabled = !loading
                        ) {
                            Text("Regenerate")
                        }
                    }
                    else -> {
                        error?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(
                            onClick = { onGenerate(includeScreenshot) },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("Generate poster")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (preview == null && !loading) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
