package com.gotcha.ui

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.gotcha.R
import com.gotcha.data.GotchaStorage
import com.gotcha.tools.FileResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The files Gotcha created for one chat, without a file-manager safari.
 * Read-only: tapping a file hands it to the system viewer through the app's
 * FileProvider (gallery, player, reader — no in-app player to maintain).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatAssetsScreen(sessionId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var loaded by remember(sessionId) { mutableStateOf(false) }
    var sections by remember(sessionId) { mutableStateOf(emptyList<ChatAssetSection>()) }
    LaunchedEffect(sessionId) {
        sections = withContext(Dispatchers.IO) {
            GotchaStorage.findChatDir(sessionId)?.let(::chatAssetSections).orEmpty()
        }
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chat_assets_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("chat_assets_back")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        if (!loaded) {
            // Still scanning; chat folders are small, no spinner.
            Unit
        } else if (sections.isEmpty()) {
            Text(
                text = stringResource(R.string.chat_assets_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
                    .testTag("chat_assets_empty")
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                sections.forEach { section ->
                    item(key = "header:${section.kind}") {
                        Text(
                            text = sectionTitle(section.kind),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(section.items, key = { it.path }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openAsset(context, File(item.path)) }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .testTag("chat_asset_row"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = item.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun sectionTitle(kind: GotchaStorage.Kind?): String = stringResource(
    when (kind) {
        GotchaStorage.Kind.PICTURES -> R.string.chat_assets_pictures
        GotchaStorage.Kind.RECORDINGS -> R.string.chat_assets_recordings
        GotchaStorage.Kind.SCREENSHOTS -> R.string.chat_assets_screenshots
        GotchaStorage.Kind.DEBUG, null -> R.string.chat_assets_other_files
    }
)

private fun openAsset(context: android.content.Context, file: File) {
    val opened = runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)
    if (!opened) {
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.chat_assets_open_failed),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

/** One file on the assets screen, with its display strings precomputed. */
data class ChatAssetItem(val path: String, val name: String, val detail: String)

/** One named group: a media kind, or null for loose files. */
data class ChatAssetSection(val kind: GotchaStorage.Kind?, val items: List<ChatAssetItem>)

/**
 * Groups a chat folder's contents for display: Pictures, Recordings and
 * Screenshots first (only when non-empty), then loose files. Newest first
 * inside each group. Sizes are read here — callers run this off the UI
 * thread — so composition never touches the disk. Pure for unit tests.
 */
fun chatAssetSections(chatDir: File): List<ChatAssetSection> {
    val sections = mutableListOf<ChatAssetSection>()
    for (kind in listOf(GotchaStorage.Kind.PICTURES, GotchaStorage.Kind.RECORDINGS, GotchaStorage.Kind.SCREENSHOTS)) {
        val files = File(chatDir, kind.dirName).listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        if (files.isNotEmpty()) sections += ChatAssetSection(kind, files.map(::toAssetItem))
    }
    val loose = chatDir.listFiles()
        ?.filter { it.isFile }
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()
    if (loose.isNotEmpty()) sections += ChatAssetSection(null, loose.map(::toAssetItem))
    return sections
}

private fun toAssetItem(file: File): ChatAssetItem = ChatAssetItem(
    path = file.absolutePath,
    name = file.name,
    detail = "${file.extension.uppercase()} · ${FileResolver.formatSizeStatic(file.length())}"
)
