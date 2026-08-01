package com.gotcha.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gotcha.agent.skills.Skill
import com.gotcha.agent.skills.SkillRegistry
import com.gotcha.data.Settings
import com.gotcha.ui.theme.SkinAlertDialog
import kotlinx.coroutines.launch

/**
 * The Skills / Plugins page: which built-in skills the agent may use, plus
 * importing, removing and host-allowlisting community skills.
 *
 * Every switch here saves immediately — there is no Save button to forget.
 */
@Composable
fun SkillsScreen(
    load: () -> Settings,
    onSave: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit
) {
    val initial = remember { load() }
    var disabledSkills by remember { mutableStateOf(initial.disabledSkills) }
    var communitySkillHosts by remember { mutableStateOf(initial.communitySkillHosts) }
    var communitySkillUrl by remember { mutableStateOf("") }
    var communitySkillPasteJson by remember { mutableStateOf("") }
    var communityImportBusy by remember { mutableStateOf(false) }
    var communitySkillRefreshTick by remember { mutableStateOf(0) }
    var communitySkillToDelete by remember { mutableStateOf<Skill?>(null) }

    val overlay = rememberSettingsOverlayState()
    val scope = rememberCoroutineScope()
    val localContext = LocalContext.current
    LaunchedEffect(Unit) {
        // Defense-in-depth: ChatViewModel also calls SkillRegistry.init, but
        // Settings can be opened before the chat screen is ever shown.
        SkillRegistry.bootstrap(localContext)
    }

    /** This page's fields, copied onto [base]. */
    fun applySkills(base: Settings) = base.copy(
        disabledSkills = disabledSkills,
        communitySkillHosts = communitySkillHosts
    )

    SettingsScaffold(title = SettingsPage.SKILLS.title, onBack = onBack, overlay = overlay) {
        val allSkills = SkillRegistry.getAllSkills()
        if (allSkills.isEmpty()) {
            Text("No skills loaded.", style = MaterialTheme.typography.bodyMedium)
        } else {
            allSkills.forEach { skill ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            skill.id,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (skill.description.isNotBlank()) {
                            Text(skill.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Switch(
                        checked = !disabledSkills.contains(skill.id),
                        onCheckedChange = { enabled ->
                            disabledSkills = if (enabled) {
                                disabledSkills - skill.id
                            } else {
                                disabledSkills + skill.id
                            }
                            onSave { applySkills(it) }
                        }
                    )
                }
            }
        }

        HorizontalDivider(thickness = 1.dp)

        // ---- Community Skills ----
        Text(
            "Community Skills",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Import skills from samosa-ai.example or paste JSON. " +
                "Community skills appear in the agent's system prompt " +
                "as advisory guidance.",
            style = MaterialTheme.typography.bodySmall
        )

        OutlinedTextField(
            value = communitySkillUrl,
            onValueChange = { communitySkillUrl = it.trim() },
            label = { Text("Skill URL (https://samosa-ai.example/...)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                if (communityImportBusy) return@Button
                val url = communitySkillUrl.trim()
                if (url.isEmpty()) {
                    overlay.show("Enter a URL first.")
                    return@Button
                }
                communityImportBusy = true
                overlay.show("Fetching skill…", sticky = true)
                scope.launch {
                    val result = runCatching {
                        val hosts = communitySkillHosts
                        SkillRegistry.importCommunityFromUrl(url, hosts)
                    }
                    communityImportBusy = false
                    result.onSuccess { skill ->
                        communitySkillUrl = ""
                        communitySkillRefreshTick++
                        overlay.show("Imported '${skill.id}'.")
                    }.onFailure { e ->
                        overlay.show(formatImportError(e))
                    }
                }
            },
            enabled = !communityImportBusy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Import from URL") }

        var pasteOpen by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { pasteOpen = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Paste JSON…") }
        if (pasteOpen) {
            androidx.compose.ui.window.Dialog(onDismissRequest = {
                pasteOpen = false
                communitySkillPasteJson = ""
            }) {
                Surface(shape = MaterialTheme.shapes.medium) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Paste community skill JSON",
                            style = MaterialTheme.typography.titleMedium
                        )
                        OutlinedTextField(
                            value = communitySkillPasteJson,
                            onValueChange = { communitySkillPasteJson = it },
                            label = { Text("Skill JSON") },
                            modifier = Modifier.fillMaxWidth().height(220.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                        )
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TextButton(onClick = {
                                pasteOpen = false
                                communitySkillPasteJson = ""
                            }) { Text("Cancel") }
                            TextButton(onClick = {
                                val src = communitySkillPasteJson.trim()
                                if (src.isEmpty()) {
                                    overlay.show("Paste JSON first.")
                                    return@TextButton
                                }
                                communityImportBusy = true
                                pasteOpen = false
                                overlay.show("Importing skill…", sticky = true)
                                scope.launch {
                                    val result = runCatching {
                                        SkillRegistry.importCommunity(src)
                                    }
                                    communityImportBusy = false
                                    communitySkillPasteJson = ""
                                    result.onSuccess { skill ->
                                        communitySkillRefreshTick++
                                        overlay.show(
                                            "Imported '${skill.id}'."
                                        )
                                    }.onFailure { e ->
                                        overlay.show(formatImportError(e))
                                    }
                                }
                            }) { Text("Import") }
                        }
                    }
                }
            }
        }

        // ---- Imported list ----
        val communitySkills = remember(communitySkillRefreshTick) {
            SkillRegistry.getCommunitySkills()
        }
        if (communitySkills.isEmpty()) {
            Text(
                "No community skills imported yet.",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            communitySkills.forEach { skill ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                if (skill.title.isNotBlank()) skill.title else skill.id,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "id: ${skill.id}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (skill.description.isNotBlank()) {
                                Text(
                                    skill.description,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Switch(
                            checked = !disabledSkills.contains(skill.id),
                            onCheckedChange = { enabled ->
                                disabledSkills = if (enabled) {
                                    disabledSkills - skill.id
                                } else {
                                    disabledSkills + skill.id
                                }
                                onSave { applySkills(it) }
                            }
                        )
                        IconButton(
                            onClick = { communitySkillToDelete = skill }
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "Delete ${skill.id}",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }

        // ---- Delete confirmation dialog ----
        communitySkillToDelete?.let { pending ->
            SkinAlertDialog(
                onDismissRequest = { communitySkillToDelete = null },
                title = { Text("Delete community skill?") },
                text = {
                    Text(
                        "Are you sure you want to permanently delete " +
                            "\"${pending.id}\"? The skill will be removed from " +
                            "this device and the agent will no longer have " +
                            "access to it. This action cannot be undone."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val id = pending.id
                            communitySkillToDelete = null
                            scope.launch {
                                runCatching { SkillRegistry.removeCommunity(id) }
                                disabledSkills = disabledSkills - id
                                onSave { applySkills(it) }
                                communitySkillRefreshTick++
                                overlay.show("Deleted '$id'.")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { communitySkillToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        HorizontalDivider(thickness = 1.dp)

        // ---- Host allowlist ----
        Text(
            "Allowed community skill hosts",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Only HTTPS hosts in this list can be fetched. " +
                "Default: samosa-ai.example.",
            style = MaterialTheme.typography.bodySmall
        )
        communitySkillHosts.forEach { host ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(host, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    communitySkillHosts = communitySkillHosts - host
                    onSave { applySkills(it) }
                }) { Text("Remove") }
            }
        }
        var newHost by remember { mutableStateOf("") }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newHost,
                onValueChange = { newHost = it.trim() },
                label = { Text("Add host") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    val h = newHost.trim()
                    if (h.isEmpty()) return@Button
                    communitySkillHosts = communitySkillHosts + h
                    onSave { applySkills(it) }
                    newHost = ""
                }
            ) { Text("Add") }
        }
    }
}
