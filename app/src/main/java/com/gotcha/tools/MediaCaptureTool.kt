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

    private val TAG = "Gotcha"
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null

    /**
     * Capture a photo automatically using CameraX. No camera app is opened.
     * Takes a photo from the selected camera in the background.
     */
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
            val dir = context.getExternalFilesDir("Pictures")
                ?: return ToolResult.error("No external storage is available for photos.")
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

    /** Start recording audio to a file (needs RECORD_AUDIO). */
    fun startAudioRecording(): ToolResult {
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
