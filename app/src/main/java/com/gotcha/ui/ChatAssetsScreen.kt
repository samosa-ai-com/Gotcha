package com.gotcha.ui

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.gotcha.ui.theme.SkinAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The files Gotcha created for one chat, browsable in place: folders open in
 * the same list, files hand off to the system viewer through the app's
 * FileProvider (gallery, player, reader — no in-app player to maintain), and
 * a long press offers open/share/delete. Read-only apart from delete, which
 * confirms first.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatAssetsScreen(sessionId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var root by remember(sessionId) { mutableStateOf<File?>(null) }
    var currentPath by remember(sessionId) { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf(emptyList<ChatAssetEntry>()) }
    // The path the visible rows belong to. Rows render only for the current
    // path: navigating (or a folder vanishing mid-scan) never leaves the
    // previous folder actionable under the new title.
    var listedPath by remember { mutableStateOf<String?>(null) }
    var menuEntry by remember { mutableStateOf<ChatAssetEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<ChatAssetEntry?>(null) }

    LaunchedEffect(sessionId) {
        root = withContext(Dispatchers.IO) { GotchaStorage.findChatDir(sessionId) }
        currentPath = root?.absolutePath
        loaded = true
    }
    LaunchedEffect(currentPath) {
        val dir = currentPath?.let(::File)?.takeIf { it.isDirectory }
        // A missing destination publishes an empty list: the old rows must
        // not survive under a folder that is gone.
        val fresh = if (dir == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { chatDirEntries(dir) }
        }
        listedPath = currentPath
        entries = fresh
    }

    val canGoUp = root != null && currentPath != null && currentPath != root?.absolutePath
    BackHandler {
        if (canGoUp) {
            currentPath = File(currentPath!!).parent
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (canGoUp) {
                            File(currentPath!!).name
                        } else {
                            stringResource(R.string.chat_assets_title)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (canGoUp) {
                                currentPath = File(currentPath!!).parent
                            } else {
                                onBack()
                            }
                        },
                        modifier = Modifier.testTag("chat_assets_back")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(
                                if (canGoUp) {
                                    R.string.chat_assets_up
                                } else {
                                    R.string.chat_assets_back
                                }
                            )
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (!loaded || listedPath != currentPath) {
            // Still resolving, or a scan in flight; chat folders are small.
            Unit
        } else if (root == null) {
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
        } else if (entries.isEmpty()) {
            Text(
                text = stringResource(
                    if (canGoUp) {
                        R.string.chat_assets_empty_folder
                    } else {
                        R.string.chat_assets_empty
                    }
                ),
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
                items(entries, key = { it.path }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    when (entry) {
                                        is ChatAssetEntry.Folder -> currentPath = entry.path
                                        is ChatAssetEntry.Asset -> openAsset(context, File(entry.path))
                                    }
                                },
                                onLongClick = { menuEntry = entry }
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("chat_asset_row"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (entry is ChatAssetEntry.Folder) {
                                Icons.Default.Folder
                            } else {
                                Icons.Default.Description
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text(
                                text = entry.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            if (entry is ChatAssetEntry.Asset) {
                                Text(
                                    text = entry.detail,
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

    menuEntry?.let { entry ->
        SkinAlertDialog(
            onDismissRequest = { menuEntry = null },
            title = { Text(entry.name) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            menuEntry = null
                            when (entry) {
                                is ChatAssetEntry.Folder -> currentPath = entry.path
                                is ChatAssetEntry.Asset -> openAsset(context, File(entry.path))
                            }
                        },
                        modifier = Modifier.testTag("chat_asset_open")
                    ) { Text(stringResource(R.string.second_brain_open)) }
                    if (entry is ChatAssetEntry.Asset) {
                        TextButton(
                            onClick = {
                                menuEntry = null
                                shareAsset(context, File(entry.path))
                            },
                            modifier = Modifier.testTag("chat_asset_share")
                        ) { Text(stringResource(R.string.action_share)) }
                    }
                    TextButton(
                        onClick = {
                            menuEntry = null
                            deleteEntry = entry
                        },
                        modifier = Modifier.testTag("chat_asset_delete")
                    ) { Text(stringResource(R.string.drawer_delete)) }
                    TextButton(
                        onClick = { menuEntry = null },
                        modifier = Modifier.testTag("chat_asset_cancel")
                    ) { Text(stringResource(R.string.action_cancel)) }
                }
            },
            confirmButton = {},
            modifier = Modifier.testTag("chat_asset_menu")
        )
    }

    deleteEntry?.let { entry ->
        SkinAlertDialog(
            onDismissRequest = { deleteEntry = null },
            title = { Text(stringResource(R.string.chat_assets_delete_title)) },
            text = { Text(stringResource(R.string.chat_assets_delete_confirm, entry.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteEntry = null
                        val pathAtDelete = currentPath
                        scope.launch(Dispatchers.IO) {
                            val target = File(entry.path)
                            val deleted = runCatching {
                                if (target.isDirectory) {
                                    target.deleteRecursively()
                                } else {
                                    target.delete()
                                }
                            }.getOrDefault(false)
                            val fresh = pathAtDelete?.let(::File)
                                ?.takeIf { it.isDirectory }
                                ?.let(::chatDirEntries)
                                .orEmpty()
                            withContext(Dispatchers.Main) {
                                // Refresh inline: the path is unchanged by a
                                // delete, so the path-watching scan would not
                                // retrigger. Skip if the user navigated away
                                // mid-delete rather than show a stale folder.
                                if (currentPath == pathAtDelete) {
                                    listedPath = pathAtDelete
                                    entries = fresh
                                }
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(
                                        if (deleted) {
                                            R.string.chat_assets_deleted
                                        } else {
                                            R.string.chat_assets_delete_failed
                                        }
                                    ),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    modifier = Modifier.testTag("chat_asset_delete_yes")
                ) { Text(stringResource(R.string.drawer_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteEntry = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            modifier = Modifier.testTag("chat_asset_delete_confirm")
        )
    }
}

private fun openAsset(context: android.content.Context, file: File) {
    val opened = runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
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

private fun shareAsset(context: android.content.Context, file: File) {
    val shared = runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)
    if (!shared) {
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.chat_assets_open_failed),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

/** One row in the browser: a subfolder, or a file with its display detail. */
sealed interface ChatAssetEntry {
    val name: String
    val path: String

    data class Folder(override val name: String, override val path: String) : ChatAssetEntry

    data class Asset(
        override val name: String,
        override val path: String,
        val detail: String
    ) : ChatAssetEntry
}

/**
 * Lists one folder's visible contents: subfolders first (A–Z), then files
 * (newest first). Dot-prefixed entries (`.debug`, `.nomedia`) stay hidden,
 * like any file manager. Sizes are read here — callers run this off the UI
 * thread — so composition never touches the disk. Pure for unit tests.
 */
fun chatDirEntries(dir: File): List<ChatAssetEntry> {
    val children = dir.listFiles()?.filter {
        !it.name.startsWith(".")
    }.orEmpty()
    val folders = children
        .filter { it.isDirectory }
        .sortedBy { it.name.lowercase() }
        .map { ChatAssetEntry.Folder(it.name, it.absolutePath) }
    val files = children
        .filter { it.isFile }
        .sortedByDescending { it.lastModified() }
        .map(::toAssetEntry)
    return folders + files
}

private fun toAssetEntry(file: File): ChatAssetEntry.Asset {
    val ext = file.extension.uppercase()
    return ChatAssetEntry.Asset(
        path = file.absolutePath,
        name = file.name,
        detail = if (ext.isEmpty()) {
            FileResolver.formatSizeStatic(file.length())
        } else {
            "$ext · ${FileResolver.formatSizeStatic(file.length())}"
        }
    )
}
