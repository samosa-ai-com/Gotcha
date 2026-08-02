package com.gotcha.notifications

import android.content.Context
import com.gotcha.data.Settings

/**
 * Top-level orchestrator for server-driven notifications. One entry point,
 * one builder — keeps `MainActivity` thin and gives the dispatcher a single
 * construction site so its dependencies can be swapped for tests.
 */
object ServerMessages {

    private const val ON_RESUME_FETCH_INTERVAL_MS = 6L * 60L * 60L * 1000L

    fun create(context: Context, settings: Settings, onUnauthorized: () -> Unit): NotificationDispatcher {
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        val api = NotificationApi(
            baseUrl = NotificationApi.DEFAULT_BASE_URL,
            bearerToken = settings.samosaSessionToken,
            onUnauthorized = onUnauthorized
        )
        return NotificationDispatcher(
            context = context,
            api = api,
            store = NotificationStore(context),
            versionName = versionName
        )
    }

    /**
     * Called on `onResume`; fetches only if the cached value in the store is
     * stale. Reads from the live [NotificationStore] — not the [Settings]
     * snapshot — so a recent fetch done elsewhere is honoured.
     */
    suspend fun syncIfStale(dispatcher: NotificationDispatcher, enabled: Boolean) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        val lastFetched = dispatcher.lastFetchedAt()
        if (lastFetched == 0L || now - lastFetched > ON_RESUME_FETCH_INTERVAL_MS) {
            dispatcher.fetchAndDeliver(now = now)
        }
    }
}
