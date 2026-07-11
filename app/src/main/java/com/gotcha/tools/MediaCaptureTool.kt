package com.gotcha.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class MediaCaptureTool(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null

    /**
     * Launch the camera app to capture a photo into the app's external Pictures dir.
     * No CAMERA permission is declared, so the intent runs as a plain hand-off; the user
     * snaps the photo and it is saved to the returned path.
     */
    fun takePhoto(): ToolResult {
        return try {
            val dir = context.getExternalFilesDir("Pictures")
                ?: return ToolResult.error("No external storage is available for photos.")
            dir.mkdirs()
            val file = File(dir, "photo_${timestamp()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolResult.error("No camera app is available on this device.")
            }
            context.startActivity(intent)
            ToolResult.ok(
                "Opened the camera. Once the user takes the photo it is saved to ${file.absolutePath}."
            )
        } catch (e: Exception) {
            ToolResult.error("Could not open the camera: ${e.message}")
        }
    }

    /** Start recording audio to a file (needs RECORD_AUDIO). */
    fun startAudioRecording(): ToolResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.permissionNeeded(
                Manifest.permission.RECORD_AUDIO,
                "Recording audio needs the Microphone permission. I have requested it — please grant it and ask again."
            )
        }
        if (recorder != null) {
            return ToolResult.error("A recording is already in progress. Stop it before starting a new one.")
        }
        return try {
            val dir = context.getExternalFilesDir("Recordings")
                ?: return ToolResult.error("No external storage is available for recordings.")
            dir.mkdirs()
            val file = File(dir, "recording_${timestamp()}.m4a")
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            recordingFile = file
            ToolResult.ok("Recording started. Ask to stop when finished.")
        } catch (e: Exception) {
            releaseRecorder()
            ToolResult.error("Could not start recording: ${e.message}")
        }
    }

    /** Stop the in-progress recording and report the saved file. */
    fun stopAudioRecording(): ToolResult {
        val rec = recorder ?: return ToolResult.error("No recording is in progress.")
        return try {
            rec.stop()
            val path = recordingFile?.absolutePath ?: "unknown"
            releaseRecorder()
            ToolResult.ok("Recording saved to $path.")
        } catch (e: Exception) {
            releaseRecorder()
            ToolResult.error("Could not stop the recording cleanly: ${e.message}")
        }
    }

    private fun releaseRecorder() {
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        recordingFile = null
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(java.util.Date())
}
