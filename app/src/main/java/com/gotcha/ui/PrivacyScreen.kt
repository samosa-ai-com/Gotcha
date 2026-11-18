package com.gotcha.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gotcha.R
import com.gotcha.data.UsageOverview
import com.gotcha.data.loadUsageOverview
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/** How many of the most-used tools the page lists. */
private const val TOP_TOOLS_SHOWN = 5

/**
 * The Privacy page: what Gotcha has recorded about its own use on this phone,
 * and where the policy for the rest lives.
 *
 * "Your usage" is computed locally from files the app already keeps; opening
 * the page sends nothing anywhere.
 */
@Composable
fun PrivacyScreen(
    onBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit
) {
    val context = LocalContext.current
    val overlay = rememberSettingsOverlayState()
    var overview by remember { mutableStateOf<UsageOverview?>(null) }
    LaunchedEffect(Unit) { overview = loadUsageOverview(context) }

    SettingsScaffold(title = stringResource(SettingsPage.PRIVACY.title), onBack = onBack, overlay = overlay) {
        Text(
            text = stringResource(R.string.privacy_your_usage_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.privacy_your_usage_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val loaded = overview
        if (loaded == null) {
            Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium)
        } else {
            UsageCard(loaded)
        }

        HorizontalDivider(thickness = 1.dp)

        Text(
            text = stringResource(R.string.privacy_where_data_goes_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.privacy_where_data_goes_body),
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedButton(
            onClick = { onOpenPage(SettingsPage.LEGAL) },
            modifier = Modifier.fillMaxWidth().testTag("privacy_open_policy")
        ) { Text(stringResource(R.string.privacy_open_policy)) }
    }
}

@Composable
private fun UsageCard(overview: UsageOverview) {
    val stats = overview.stats
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val percent = remember { NumberFormat.getPercentInstance() }
    fun count(n: Int) = numbers.format(n)
    fun share(part: Int, whole: Int) = if (whole == 0) "—" else percent.format(part.toDouble() / whole)

    OutlinedCard(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("privacy_usage_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatRow(stringResource(R.string.privacy_stat_chats), count(stats.chatSessions))
            StatRow(stringResource(R.string.privacy_stat_calls), count(stats.voiceCallSessions))
            StatRow(stringResource(R.string.privacy_stat_requests), count(stats.totalRuns))
            StatRow(
                stringResource(R.string.privacy_stat_requests_recent, UsageOverview.RECENT_DAYS),
                count(overview.recentRuns)
            )
            StatRow(
                stringResource(R.string.privacy_stat_success),
                share(stats.successfulRuns, stats.totalRuns)
            )
            StatRow(
                stringResource(R.string.privacy_stat_operator),
                share(stats.operatorRuns, stats.totalRuns)
            )
            StatRow(
                stringResource(R.string.privacy_stat_tool_calls),
                stringResource(
                    R.string.privacy_stat_ok_failed,
                    count(stats.successfulToolCalls),
                    count(stats.totalToolCalls - stats.successfulToolCalls)
                )
            )
            StatRow(stringResource(R.string.privacy_stat_scheduled), count(overview.activeScheduledTasks))
            StatRow(stringResource(R.string.privacy_stat_crashes), count(overview.crashes))
            overview.firstRunAt?.let {
                StatRow(
                    stringResource(R.string.privacy_stat_first_use),
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))
                )
            }

            val topTools = stats.toolCallsByName.entries
                .sortedByDescending { it.value }
                .take(TOP_TOOLS_SHOWN)
            if (topTools.isNotEmpty()) {
                HorizontalDivider(thickness = 1.dp)
                Text(
                    text = stringResource(R.string.privacy_stat_top_tools),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                topTools.forEach { StatRow(it.key, count(it.value)) }
            }
            if (stats.models.isNotEmpty()) {
                HorizontalDivider(thickness = 1.dp)
                Text(
                    text = stringResource(R.string.privacy_stat_models),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stats.models.sorted().joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
