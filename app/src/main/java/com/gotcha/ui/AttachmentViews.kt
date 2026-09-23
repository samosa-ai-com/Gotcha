package com.gotcha.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gotcha.agent.Attachment
import com.gotcha.agent.ComposerAttachment
import com.gotcha.tools.FileResolver
import com.gotcha.ui.theme.LocalSkin

/** Decodes an attachment's base64 JPEG once per image, or null if it is unreadable. */
@Composable
private fun rememberThumbnail(base64: String): Bitmap? = remember(base64) {
    try {
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: Exception) {
        null
    }
}

/**
 * The composer's queue of files waiting for Send, in picked order: image
 * thumbnails and document chips, each with its own remove button, plus a count
 * against [ComposerAttachment.MAX_PER_MESSAGE].
 */
@Composable
fun ComposerAttachmentStrip(
    attachments: List<ComposerAttachment>,
    onRemove: (String) -> Unit
) {
    val skin = LocalSkin.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.testTag("composer_attachments")
        ) {
            items(attachments, key = { it.id }) { attachment ->
                when (attachment) {
                    is ComposerAttachment.Image -> Box {
                        val bitmap = rememberThumbnail(attachment.base64)
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = attachment.name,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(skin.cornerSmall)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(skin.cornerSmall))
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                            )
                        }
                        RemoveButton(
                            name = attachment.name,
                            onClick = { onRemove(attachment.id) },
                            modifier = Modifier.align(Alignment.TopEnd)
                        )
                    }
                    is ComposerAttachment.Document -> Row(
                        modifier = Modifier
                            .width(200.dp)
                            .height(72.dp)
                            .clip(RoundedCornerShape(skin.cornerSmall))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DocumentSummary(
                            attachment = attachment.attachment,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onRemove(attachment.id) }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove ${attachment.name}",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }
        Text(
            "${attachments.size} / ${ComposerAttachment.MAX_PER_MESSAGE} files",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 2.dp)
        )
    }
}

/** Small round ✕ laid over an image thumbnail. */
@Composable
private fun RemoveButton(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(28.dp)) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove $name",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * The files a sent user message carried: images first (one full-width, or a
 * scrolling row of thumbnails when there are several), then a chip per document.
 */
@Composable
fun SentAttachments(attachments: List<ComposerAttachment>, contentColor: Color) {
    val skin = LocalSkin.current
    val images = attachments.filterIsInstance<ComposerAttachment.Image>()
    val documents = attachments.filterIsInstance<ComposerAttachment.Document>()
    if (images.size == 1) {
        SentImage(images.single(), Modifier.fillMaxWidth(), 160.dp, ContentScale.Fit)
    } else if (images.size > 1) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            images.forEach { SentImage(it, Modifier.size(120.dp), 120.dp, ContentScale.Crop) }
        }
    }
    documents.forEachIndexed { index, document ->
        if (index > 0 || images.isNotEmpty()) Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(skin.cornerSmall))
                .background(contentColor.copy(alpha = 0.08f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DocumentSummary(document.attachment, contentColor, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SentImage(image: ComposerAttachment.Image, modifier: Modifier, height: Dp, scale: ContentScale) {
    val bitmap = rememberThumbnail(image.base64) ?: return
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = image.name,
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(8.dp)),
        contentScale = scale
    )
}

/** File icon, name, and "type · size" line for a document attachment. */
@Composable
private fun DocumentSummary(attachment: Attachment, color: Color, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = color.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                attachment.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append(attachment.mimeType.ifBlank { "document" })
                    append(" · ")
                    append(FileResolver.formatSizeStatic(attachment.size))
                    if (attachment.truncated) append(" · truncated")
                },
                style = MaterialTheme.typography.bodySmall,
                color = color.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
