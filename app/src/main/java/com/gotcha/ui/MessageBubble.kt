package com.gotcha.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gotcha.agent.MessageKind
import com.gotcha.agent.UiMessage

@Composable
fun MessageBubble(message: UiMessage) {
    val isUser = message.kind == MessageKind.USER
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (message.kind) {
        MessageKind.USER -> colors.primaryContainer to colors.onPrimaryContainer
        MessageKind.ASSISTANT -> colors.surfaceVariant to colors.onSurfaceVariant
        MessageKind.TOOL -> colors.secondaryContainer to colors.onSecondaryContainer
        MessageKind.ERROR -> colors.errorContainer to colors.onErrorContainer
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = container,
            contentColor = content,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            )
        ) {
            Box(modifier = Modifier.widthIn(max = 300.dp).padding(12.dp)) {
                Text(
                    text = when (message.kind) {
                        MessageKind.TOOL -> "🔧 ${message.text}"
                        else -> message.text
                    },
                    style = when (message.kind) {
                        MessageKind.TOOL -> MaterialTheme.typography.bodySmall
                        else -> MaterialTheme.typography.bodyMedium
                    }
                )
            }
        }
    }
}
