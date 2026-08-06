package com.gotcha.connectors

import android.content.Context
import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import com.gotcha.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background runner and scheduler for refreshing tools & connection health across
 * active connectors.
 */
class ConnectorRefreshScheduler(
    private val context: Context? = null,
    private val store: SettingsStore = context?.let { SettingsRepository(it) }
        ?: throw IllegalArgumentException("Context or SettingsStore required"),
    private val clock: () -> Long = System::currentTimeMillis,
    private val refreshAction: (suspend (disabledConnectors: Set<String>) -> Map<String, String>)? = null
) {

    /** Secondary constructor for standard Context-based instantiation. */
    constructor(context: Context) : this(
        context = context,
        store = SettingsRepository(context),
        clock = System::currentTimeMillis,
        refreshAction = null
    )

    /**
     * Checks whether a refresh is due based on [Settings.connectorAutoRefreshIntervalMinutes]
     * and executes connector refreshes if due (or if [force] is true).
     *
     * Returns a map of connector ID -> status message, or an empty map if refresh was skipped.
     */
    suspend fun refreshIfNeeded(force: Boolean = false): Map<String, String> = withContext(Dispatchers.IO) {
        val settings = store.load()
        val intervalMinutes = settings.connectorAutoRefreshIntervalMinutes
        if (!force && intervalMinutes <= 0) return@withContext emptyMap()

        val now = clock()
        val intervalMillis = intervalMinutes * 60_000L
        val elapsed = now - settings.connectorLastRefreshedAt

        if (!force && elapsed < intervalMillis) {
            return@withContext emptyMap()
        }

        val results = if (refreshAction != null) {
            refreshAction.invoke(settings.disabledConnectors)
        } else {
            val ctx = checkNotNull(context) { "Context required for default refresh action" }
            ConnectorRegistry.init(ctx)
            ConnectorRegistry.refreshAllActive(settings.disabledConnectors)
        }

        store.save(store.load().copy(connectorLastRefreshedAt = now))
        results
    }
}
