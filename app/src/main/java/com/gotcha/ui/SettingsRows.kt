package com.gotcha.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A row that opens a settings page: the page's icon, its title and a summary, and
 * the text chevron the settings pages use for "goes somewhere".
 */
@Composable
fun SettingsNavRow(
    page: SettingsPage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Overrides the row label. Search results pass the "hub › page" path. */
    title: String = stringResource(page.title),
    /** The home list keeps summaries to one line so a whole section fits on screen. */
    summaryMaxLines: Int = 2,
    /** Inside a group card the row brings its own side padding; on a plain page it has none. */
    horizontalPadding: Dp = 0.dp
) {
    SettingsLinkRow(
        icon = page.icon,
        title = title,
        summary = stringResource(page.summary),
        onClick = onClick,
        modifier = modifier,
        summaryMaxLines = summaryMaxLines,
        horizontalPadding = horizontalPadding
    )
}

/** The shape of [SettingsNavRow], for rows that run an action instead of opening a page. */
@Composable
fun SettingsLinkRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summaryMaxLines: Int = 2,
    horizontalPadding: Dp = 0.dp
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = summaryMaxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(text = "›", style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * One headed section of the settings home list: a small heading, then its rows on
 * a single rounded card, divided by inset lines rather than full-width rules.
 */
@Composable
fun SettingsGroupCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
        }
        OutlinedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

/** The line between two rows on a [SettingsGroupCard], indented past the icon. */
@Composable
fun SettingsGroupDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        modifier = Modifier.padding(start = 56.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}
