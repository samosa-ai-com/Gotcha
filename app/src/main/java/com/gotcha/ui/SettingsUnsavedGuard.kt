package com.gotcha.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.gotcha.R
import com.gotcha.ui.theme.SkinAlertDialog

/**
 * Keeps a settings page from losing typed edits on the way out.
 *
 * Switches and pickers save the moment they change. Pages that also hold text
 * fields — or, on AI model, a provider and model that shouldn't apply halfway
 * through an edit — keep a Save button instead, and before this nothing stopped
 * Back from throwing those edits away without a word.
 *
 * While [dirty], both the system Back and the returned function (meant for the
 * page's own "← Back") ask Save / Discard first; when clean they just call
 * [onLeave]. Dismissing the dialog keeps the user on the page, edits intact.
 *
 * Pass [canSave] as the same check that enables the page's Save button. While
 * it is false the dialog says the page is incomplete and offers Keep editing
 * instead of Save, so leaving can never store what the button would refuse.
 */
@Composable
fun rememberUnsavedChangesGuard(
    dirty: Boolean,
    onSave: () -> Unit,
    onLeave: () -> Unit,
    canSave: Boolean = true
): () -> Unit {
    var asking by remember { mutableStateOf(false) }
    val back = { if (dirty) asking = true else onLeave() }

    BackHandler(enabled = dirty) { asking = true }

    if (asking) {
        SkinAlertDialog(
            onDismissRequest = { asking = false },
            modifier = Modifier.testTag("settings_unsaved_dialog"),
            title = { Text(stringResource(R.string.settings_unsaved_title)) },
            text = {
                Text(
                    stringResource(
                        if (canSave) R.string.settings_unsaved_message else R.string.settings_unsaved_incomplete
                    )
                )
            },
            confirmButton = {
                if (canSave) {
                    TextButton(
                        onClick = {
                            asking = false
                            onSave()
                            onLeave()
                        },
                        modifier = Modifier.testTag("settings_unsaved_save")
                    ) { Text(stringResource(R.string.settings_unsaved_save)) }
                } else {
                    TextButton(
                        onClick = { asking = false },
                        modifier = Modifier.testTag("settings_unsaved_keep_editing")
                    ) { Text(stringResource(R.string.settings_unsaved_keep_editing)) }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        asking = false
                        onLeave()
                    },
                    modifier = Modifier.testTag("settings_unsaved_discard")
                ) { Text(stringResource(R.string.settings_unsaved_discard)) }
            }
        )
    }
    return back
}
