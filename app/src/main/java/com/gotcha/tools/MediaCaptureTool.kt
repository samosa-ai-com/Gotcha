package com.gotcha.tools

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.provider.MediaStore
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

    private val TAG = "Gotcha"
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingStartTime: Long = 0L
    private var recordingPaused: Boolean = false

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
            val dir = File(FileResolver.WORKING_DIR_BASE, "Pictures")
            dir.mkdirs()
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
            return ToolResult.error("A recording is already in progress. Stop it before starting a new one.")
        }
        return try {
            val file = if (!outputPath.isNullOrBlank()) {
                val resolved = if (outputPath.startsWith("/")) outputPath
                    else File(FileResolver.WORKING_DIR_BASE, outputPath).absolutePath
                File(resolved).also { it.parentFile?.mkdirs() }
            } else {
                val dir = File(FileResolver.WORKING_DIR_BASE, "Recordings")
                dir.mkdirs()
                File(dir, "recording_${timestamp()}.m4a")
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
                "low" -> { rec.setAudioSamplingRate(16000); rec.setAudioEncodingBitRate(16000) }
                "medium" -> { rec.setAudioSamplingRate(44100); rec.setAudioEncodingBitRate(64000) }
                "high" -> { rec.setAudioSamplingRate(44100); rec.setAudioEncodingBitRate(192000) }
            }

            if (maxDurationSeconds != null && maxDurationSeconds > 0) {
                rec.setMaxDuration(maxDurationSeconds * 1000)
            }
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            recordingFile = file
            recordingStartTime = System.currentTimeMillis()
            recordingPaused = false

            val extras = buildString {
                if (source != null) append(" ($source)")
                if (maxDurationSeconds != null && maxDurationSeconds > 0) append(", max ${maxDurationSeconds}s")
                append(" at ${file.absolutePath}")
                if (quality != null) append(", $quality quality")
            }
            ToolResult.ok("Recording started$extras.")
        } catch (e: Exception) {
            releaseRecorder()
            ToolResult.error("Could not start recording: ${e.message}")
        }
    }

    fun stopAudioRecording(): ToolResult {
        val rec = recorder ?: return ToolResult.error("No recording is in progress.")
        return try {
            rec.stop()
            val file = recordingFile
            val path = file?.absolutePath ?: "unknown"
            val dur = (System.currentTimeMillis() - recordingStartTime) / 1000
            // Make the file accessible to other apps
            file?.setReadable(true, false)
            // Scan into MediaStore so music players can discover it
            if (file != null) {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                        put(MediaStore.Audio.Media.DATA, file.absolutePath)
                        put(MediaStore.Audio.Media.TITLE, file.nameWithoutExtension)
                        put(MediaStore.Audio.Media.DURATION, dur * 1000)
                        put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                    }
                    context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                } catch (_: Exception) { }
            }
            releaseRecorder()
            ToolResult.ok("Recording saved to $path (${dur / 60}m ${dur % 60}s).")
        } catch (e: Exception) {
            releaseRecorder()
            ToolResult.error("Could not stop the recording cleanly: ${e.message}")
        }
    }

    fun getAudioRecordingStatus(): ToolResult {
        val rec = recorder
        if (rec == null) return ToolResult.ok("No recording is active.")
        val elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000
        val path = recordingFile?.absolutePath ?: "unknown"
        val state = if (recordingPaused) "paused" else "recording"
        return ToolResult.ok("$state for ${elapsed / 60}m ${elapsed % 60}s at $path.")
    }

    fun pauseAudioRecording(): ToolResult {
        val rec = recorder ?: return ToolResult.error("No recording is in progress.")
        if (recordingPaused) return ToolResult.error("Recording is already paused.")
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
        val rec = recorder ?: return ToolResult.error("No recording is in progress.")
        if (!recordingPaused) return ToolResult.error("Recording is not paused.")
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
        recorder = null
        recordingFile = null
        recordingStartTime = 0L
        recordingPaused = false
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(java.util.Date())
}
