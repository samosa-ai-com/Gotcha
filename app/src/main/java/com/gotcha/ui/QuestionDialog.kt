package com.gotcha.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gotcha.agent.PendingQuestion
import com.gotcha.ui.theme.SkinAlertDialog

const val QUESTION_DIALOG_TITLE = "Gotcha has a question"

/**
 * The agent's question to the user (the `question` tool). The question can run
 * to several paragraphs with steps and commands, so it is Markdown in the
 * scrollable body under a short fixed title, never the title itself (issue
 * #107); Skip stays pinned below it, reachable however long the question is.
 * [onAnswer] gets the chosen or typed answer, or null for Skip.
 */
@Composable
fun QuestionDialog(pending: PendingQuestion, onAnswer: (String?) -> Unit) {
    var customAnswer by remember(pending) { mutableStateOf("") }
    SkinAlertDialog(
        onDismissRequest = { onAnswer(null) },
        title = { Text(QUESTION_DIALOG_TITLE) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RichMarkdown(pending.question)
                pending.options.forEach { option ->
                    Button(onClick = { onAnswer(option) }, modifier = Modifier.fillMaxWidth()) {
                        Text(option)
                    }
                }
                if (pending.allowCustom || pending.options.isEmpty()) {
                    OutlinedTextField(
                        value = customAnswer,
                        onValueChange = { customAnswer = it },
                        label = { Text("Your answer") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onAnswer(customAnswer.trim()) },
                        enabled = customAnswer.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Submit")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(null) }) { Text("Skip") } }
    )
}
