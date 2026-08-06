package com.gotcha.connectors

import android.content.Context
import com.gotcha.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background runner and scheduler for refreshing tools & connection health across
 * active connectors.
 */
class ConnectorRefreshScheduler(
    private val context: Context,
    private val repository: SettingsRepository = SettingsRepository(context),
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * Checks whether a refresh is due based on [Settings.connectorAutoRefreshIntervalMinutes]
     * and executes [ConnectorRegistry.refreshAllActive] if due (or if [force] is true).
     *
     * Returns a map of connector ID -> status message, or an empty map if refresh was skipped.
     */
    suspend fun refreshIfNeeded(force: Boolean = false): Map<String, String> = withContext(Dispatchers.IO) {
        val settings = repository.load()
        val intervalMinutes = settings.connectorAutoRefreshIntervalMinutes
        if (!force && intervalMinutes <= 0) return@withContext emptyMap()

        val now = clock()
        val intervalMillis = intervalMinutes * 60_000L
        val elapsed = now - settings.connectorLastRefreshedAt

        if (!force && elapsed < intervalMillis) {
            return@withContext emptyMap()
        }

        ConnectorRegistry.init(context)
        val results = ConnectorRegistry.refreshAllActive(settings.disabledConnectors)
        repository.save(settings.copy(connectorLastRefreshedAt = now))
        results
    }
}
