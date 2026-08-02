package com.gotcha.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import com.gotcha.agent.ChatViewModel
import com.gotcha.data.GotchaStorage
import com.gotcha.data.RunSummary
import kotlinx.coroutines.launch

/**
 * State + flow for the "Share your Gotcha moment" poster sheet.
 *
 * Owns the sheet's open/loading/preview/error state and the generate/share/save
 * actions so [android.app.Activity] hosts stay thin. The LLM copy call runs on
 * [ChatViewModel.viewModelScope]; the render hops to the main thread inside
 * [ChatViewModel.generateShareCard].
 */
class SharePosterState(
    private val context: Context,
    private val viewModel: ChatViewModel
) {
    /** Runs the poster is being built from (null = sheet closed). */
    var runs by mutableStateOf<List<RunSummary>?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var preview by mutableStateOf<Bitmap?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Opens the poster sheet for [runs] (single latest run or whole chat). */
    fun open(runs: List<RunSummary>) {
        if (runs.isEmpty()) {
            Toast.makeText(context, "Nothing to share yet.", Toast.LENGTH_SHORT).show()
            return
        }
        this.runs = runs
        loading = false
        preview = null
        error = null
    }

    /** Generates the poster (one LLM call + render) for the sheet's [runs]. */
    fun generate(includeScreenshot: Boolean) {
        val runs = this.runs ?: return
        loading = true
        preview = null
        error = null
        viewModel.viewModelScope.launch {
            val result = viewModel.generateShareCard(runs, includeScreenshot)
            loading = false
            result.fold(
                onSuccess = { preview = it },
                onFailure = { e ->
                    error = e.message ?: "Could not generate the poster."
                }
            )
        }
    }

    /** Shares the generated poster via the Android share sheet (FileProvider). */
    fun share(bitmap: Bitmap) {
        try {
            val dir = java.io.File(context.cacheDir, "poster")
            dir.mkdirs()
            val file = java.io.File(dir, "gotcha_moment_${System.currentTimeMillis()}.png")
            file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Gotcha just did this for me")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share your Gotcha moment"))
        } catch (e: Exception) {
            Toast.makeText(context, "Could not share: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Saves the generated poster to the device gallery. */
    fun save(bitmap: Bitmap) {
        try {
            val location = GotchaStorage.saveScreenshot(
                context,
                "gotcha_moment_${System.currentTimeMillis()}.png",
                bitmap
            )
            Toast.makeText(context, "Saved to $location", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Could not save: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Closes the sheet. */
    fun dismiss() {
        runs = null
    }
}
