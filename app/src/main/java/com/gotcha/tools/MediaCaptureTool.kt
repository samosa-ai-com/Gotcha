package com.gotcha.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class MediaCaptureTool(private val context: Context) {

    private companion object {
        const val TAG = "Gotcha"
    }

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingUri: android.net.Uri? = null
    private var recordingPfd: android.os.ParcelFileDescriptor? = null
    private var recordingDisplayPath: String = ""
    private var recordingStartTime: Long = 0L
    private var recordingPaused: Boolean = false

    /**
     * Where the last completed recording was saved, and how long it ran.
     * Kept so a repeated [stopAudioRecording] can report the already-saved file
     * instead of a bare "no recording in progress" error — models routinely
     * issue a second, redundant stop, and an error there reads as a failure.
     */
    private var lastSavedPath: String? = null
    private var lastSavedDurationSeconds: Long = 0L

    suspend fun takePhoto(camera: String?): ToolResult {
        Log.d(TAG, "takePhoto: camera=$camera")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.permissionNeeded(
                Manifest.permission.CAMERA,
                "The Camera permission is not granted. Go to Settings → Permissions → Camera and enable it, then ask again."
            )
        }
        val lifecycleOwner = com.gotcha.MainActivity.lifecycleOwner
            ?: return ToolResult.error("Camera system is not ready yet. Try again in a moment.")

        return try {
            val dir = com.gotcha.data.GotchaStorage.subdir(
                File(FileResolver.WORKING_DIR_BASE),
                com.gotcha.data.GotchaStorage.Kind.PICTURES
            )
            val file = File(dir, "photo_${timestamp()}.jpg")

            val cameraSelector = if (camera?.trim()?.lowercase() == "front") {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

            val result = withContext(Dispatchers.Main) {
                Log.d(TAG, "takePhoto: on main thread, acquiring provider…")
                val cameraProvider = ProcessCameraProvider.getInstance(context).get(5, TimeUnit.SECONDS)
                Log.d(TAG, "takePhoto: provider acquired, binding…")
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageCapture)

                val res = suspendCancellableCoroutine<ToolResult> { cont ->
                    Log.d(TAG, "takePhoto: calling takePicture…")
                    imageCapture.takePicture(
                        outputOptions,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                Log.d(TAG, "takePhoto: image saved to ${file.absolutePath}")
                                com.gotcha.data.GotchaStorage.publishToGallery(context, file)
                                cont.resume(ToolResult.ok("Photo saved to ${file.absolutePath}."))
                            }
                            override fun onError(exception: ImageCaptureException) {
                                Log.e(TAG, "takePhoto: error: ${exception.message}")
                                cont.resume(ToolResult.error("Camera error: ${exception.message}"))
                            }
                        }
                    )
                }
                cameraProvider.unbindAll()
                res
            }
            result
        } catch (e: Exception) {
            ToolResult.error("Could not take photo: ${e.message}")
        }
    }

    fun startAudioRecording(
        source: String? = null,
        maxDurationSeconds: Int? = null,
        outputPath: String? = null,
        quality: String? = null
    ): ToolResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.permissionNeeded(
                Manifest.permission.RECORD_AUDIO,
                "The Microphone permission is not granted. Go to Settings → Permissions → Microphone and enable it, then ask again."
            )
        }
        if (recorder != null) {
            return ToolResult.error(
                "A recording is already in progress. You may stop it with stop_audio_recording before starting a new one, or check " +
                    "status with get_audio_recording_status."
            )
        }
        // Drop the previous recording's details up front: if this start fails, a
        // following stop must report the real error, not a stale saved file.
        lastSavedPath = null
        lastSavedDurationSeconds = 0L
        recordingFile = null
        recordingUri = null
        recordingPfd = null
        return try {
            if (!outputPath.isNullOrBlank()) {
                val resolved = FileResolver(context).resolveForWrite(outputPath)
                val file = when (resolved) {
                    is FileResolver.ResolveResult.Ok -> resolved.file.also { it.parentFile?.mkdirs() }
                    is FileResolver.ResolveResult.PermissionNeeded -> return resolved.result
                    is FileResolver.ResolveResult.Error -> return ToolResult.error(resolved.message)
                }
                recordingFile = file
                recordingDisplayPath = file.absolutePath
            } else {
                // Default location: the system-wide public Recordings folder, not
                // the per-chat working directory — recordings should be findable
                // like any other device recording, independent of which chat made them.
                when (
                    val target = com.gotcha.data.GotchaStorage.createRecordingTarget(
                        context,
                        "recording_${timestamp()}.m4a"
                    )
                ) {
                    is com.gotcha.data.GotchaStorage.RecordingTarget.DirectFile -> {
                        recordingFile = target.file
                        recordingDisplayPath = target.file.absolutePath
                    }
                    is com.gotcha.data.GotchaStorage.RecordingTarget.MediaStoreEntry -> {
                        recordingUri = target.uri
                        recordingPfd = target.pfd
                        recordingDisplayPath = target.displayPath
                    }
                }
            }

            val audioSource = when (source?.trim()?.lowercase()) {
                "voice" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
                "camcorder" -> MediaRecorder.AudioSource.CAMCORDER
                else -> MediaRecorder.AudioSource.MIC
            }

            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.setAudioSource(audioSource)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

            when (quality?.trim()?.lowercase()) {
                "low" -> {
                    rec.setAudioSamplingRate(16000)
                    rec.setAudioEncodingBitRate(16000)
                }
                "medium" -> {
                    rec.setAudioSamplingRate(44100)
                    rec.setAudioEncodingBitRate(64000)
                }
                "high" -> {
                    rec.setAudioSamplingRate(44100)
                    rec.setAudioEncodingBitRate(192000)
                }
            }

            if (maxDurationSeconds != null && maxDurationSeconds > 0) {
                rec.setMaxDuration(maxDurationSeconds * 1000)
            }
            val pfd = recordingPfd
            if (pfd != null) {
                rec.setOutputFile(pfd.fileDescriptor)
            } else {
                rec.setOutputFile(recordingFile!!.absolutePath)
            }
            rec.prepare()
            rec.start()
            recorder = rec
            recordingStartTime = System.currentTimeMillis()
            recordingPaused = false

            val extras = buildString {
                if (source != null) append(" ($source)")
                if (maxDurationSeconds != null && maxDurationSeconds > 0) append(", max ${maxDurationSeconds}s")
                append(" at $recordingDisplayPath")
                if (quality != null) append(", $quality quality")
            }
            ToolResult.ok("Recording started$extras.")
        } catch (e: Exception) {
            recordingUri?.let { com.gotcha.data.GotchaStorage.discardPendingRecording(context, it) }
            releaseRecorder()
            ToolResult.error("Could not start recording: ${e.message}")
        }
    }

    fun stopAudioRecording(): ToolResult {
        val rec = recorder ?: return alreadyStoppedResult()
        return try {
            rec.stop()
            val path = recordingDisplayPath
            val dur = (System.currentTimeMillis() - recordingStartTime) / 1000
            val file = recordingFile
            val uri = recordingUri
            if (file != null) {
                // Make the file accessible to other apps
                file.setReadable(true, false)
                com.gotcha.data.GotchaStorage.publishToGallery(context, file)
            }
            if (uri != null) {
                com.gotcha.data.GotchaStorage.finalizeRecording(context, uri)
            }
            releaseRecorder()
            lastSavedPath = path
            lastSavedDurationSeconds = dur
            ToolResult.ok("Recording saved to $path (${formatDuration(dur)}).")
        } catch (e: Exception) {
            releaseRecorder()
            ToolResult.error("Could not stop the recording cleanly: ${e.message}")
        }
    }

    /**
     * Nothing is recording. If a recording was already stopped and saved, that is
     * the state the caller wanted, so report success with the saved file rather
     * than an error — otherwise a redundant second stop looks like a failure.
     */
    private fun alreadyStoppedResult(): ToolResult {
        val path = lastSavedPath ?: return ToolResult.error(
            "No recording is in progress. Use start_audio_recording to begin one, or check " +
                "status with get_audio_recording_status."
        )
        return ToolResult.ok(
            "No recording is currently running — it was already stopped and saved to $path " +
                "(${formatDuration(lastSavedDurationSeconds)}). Nothing further to do."
        )
    }

    private fun formatDuration(seconds: Long): String = "${seconds / 60}m ${seconds % 60}s"

    fun getAudioRecordingStatus(): ToolResult {
        val rec = recorder
        if (rec == null) return ToolResult.ok("No recording is active.")
        val elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000
        val path = recordingDisplayPath.ifEmpty { "unknown" }
        val state = if (recordingPaused) "paused" else "recording"
        return ToolResult.ok("$state for ${elapsed / 60}m ${elapsed % 60}s at $path.")
    }

    fun pauseAudioRecording(): ToolResult {
        val rec = recorder ?: return ToolResult.error(
            "No recording is in progress. Use start_audio_recording to begin one, or check " +
                "status with get_audio_recording_status."
        )
        if (recordingPaused) {
            return ToolResult.error(
                "Recording is already paused. You may resume it with resume_audio_recording or stop it with stop_audio_recording."
            )
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return ToolResult.error("Pause/resume requires Android 7.0+ (API 24).")
        }
        return try {
            rec.pause()
            recordingPaused = true
            ToolResult.ok("Recording paused.")
        } catch (e: Exception) {
            ToolResult.error("Could not pause: ${e.message}")
        }
    }

    fun resumeAudioRecording(): ToolResult {
        val rec = recorder ?: return ToolResult.error(
            "No recording is in progress. Use start_audio_recording to begin one, or check " +
                "status with get_audio_recording_status."
        )
        if (!recordingPaused) {
            return ToolResult.error(
                "Recording is not paused. Use pause_audio_recording first, or stop the recording with stop_audio_recording."
            )
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return ToolResult.error("Pause/resume requires Android 7.0+ (API 24).")
        }
        return try {
            rec.resume()
            recordingPaused = false
            ToolResult.ok("Recording resumed.")
        } catch (e: Exception) {
            ToolResult.error("Could not resume: ${e.message}")
        }
    }

    private fun releaseRecorder() {
        try { recorder?.release() } catch (_: Exception) {}
        try { recordingPfd?.close() } catch (_: Exception) {}
        recorder = null
        recordingFile = null
        recordingUri = null
        recordingPfd = null
        recordingDisplayPath = ""
        recordingStartTime = 0L
        recordingPaused = false
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(java.util.Date())
}
