package com.gotcha.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gotcha.R
import com.gotcha.tools.AgentMode
import com.gotcha.ui.theme.fade

/**
 * What the page says before Gotcha can read its memory: what the thing is,
 * what it buys the user, and the one system toggle that stands in the way.
 * The old copy named the toggle but left the journey to the user.
 */
@Composable
internal fun SecondBrainAccessCard(onGrant: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.fade(0.4f),
        modifier = Modifier.fillMaxWidth().testTag("settings_second_brain_setup")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.second_brain_setup_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                stringResource(R.string.second_brain_setup_body),
                style = MaterialTheme.typography.bodyMedium
            )
            listOf(
                R.string.second_brain_setup_remembers,
                R.string.second_brain_setup_grows,
                R.string.second_brain_setup_yours
            ).forEach { line ->
                Text(
                    "• " + stringResource(line),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onGrant,
                modifier = Modifier.fillMaxWidth().testTag("settings_second_brain_grant")
            ) { Text(stringResource(R.string.second_brain_setup_grant)) }
            Text(
                stringResource(R.string.second_brain_setup_grant_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * A vault holding only the seeded skeleton. Two prompts rather than an empty
 * list: one starts the memory, the other shows what it is for. Both fill the
 * composer and stop, like every other starter in the app.
 */
@Composable
internal fun SecondBrainStartCard(onStartChat: (String, AgentMode) -> Unit) {
    val meetPrompt = stringResource(R.string.second_brain_start_meet_prompt)
    val recallPrompt = stringResource(R.string.second_brain_start_recall_prompt)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.fade(0.4f),
        modifier = Modifier.fillMaxWidth().testTag("settings_second_brain_start")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.second_brain_start_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                stringResource(R.string.second_brain_start_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                // Saving memory is a write, so this chat opens in Operator.
                onClick = { onStartChat(meetPrompt, AgentMode.OPERATOR) },
                modifier = Modifier.fillMaxWidth().testTag("settings_second_brain_start_meet")
            ) { Text(stringResource(R.string.second_brain_start_meet)) }
            OutlinedButton(
                onClick = { onStartChat(recallPrompt, AgentMode.MONITOR) },
                modifier = Modifier.fillMaxWidth().testTag("settings_second_brain_start_recall")
            ) { Text(stringResource(R.string.second_brain_start_recall)) }
        }
    }
}
