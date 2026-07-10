package com.gotcha.tools

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class WallpaperTool(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun setWallpaper(url: String?): ToolResult = withContext(Dispatchers.IO) {
        val imageUrl = url?.takeIf { it.isNotBlank() } ?: DEFAULT_RANDOM_URL
        if (!imageUrl.startsWith("https://") && !imageUrl.startsWith("http://")) {
            return@withContext ToolResult.error("'$imageUrl' is not a valid http(s) image URL.")
        }
        try {
            val request = Request.Builder().url(imageUrl).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ToolResult.error(
                        "Image download failed (HTTP ${response.code}) from $imageUrl."
                    )
                }
                val body = response.body
                    ?: return@withContext ToolResult.error("Empty response from $imageUrl.")
                val bitmap = BitmapFactory.decodeStream(body.byteStream())
                    ?: return@withContext ToolResult.error("Downloaded data is not a decodable image.")
                WallpaperManager.getInstance(context).setBitmap(bitmap)
            }
            ToolResult.ok("Wallpaper updated from $imageUrl.")
        } catch (e: SecurityException) {
            ToolResult.error("Missing SET_WALLPAPER permission: ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("Could not set wallpaper: ${e.message}")
        }
    }

    companion object {
        private const val DEFAULT_RANDOM_URL = "https://picsum.photos/1080/1920"
    }
}
