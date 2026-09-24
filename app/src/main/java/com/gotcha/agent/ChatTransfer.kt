package com.gotcha.agent

import com.gotcha.data.ImportPreview

/**
 * Where a chat backup or import (issue #83) stands, for the dialogs that walk
 * the user through it. One at a time: a new backup or import replaces whatever
 * report was still open.
 */
sealed interface ChatTransferState {
    object Idle : ChatTransferState

    /** Reading or writing a file; [message] says which. */
    data class Working(val message: String) : ChatTransferState

    /** A readable import file, waiting for the user to confirm. */
    data class Previewing(val preview: ImportPreview) : ChatTransferState

    /** The outcome: a one-line [summary] and anything the user should know chat by chat. */
    data class Report(val title: String, val summary: String, val details: List<String> = emptyList()) :
        ChatTransferState
}

/** A backup the user asked for, held while the system file picker is open. */
data class BackupRequest(
    /** The chat to back up, or null for every chat. */
    val sessionId: String?,
    val includeImages: Boolean
)
