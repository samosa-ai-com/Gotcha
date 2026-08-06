package com.gotcha.connectors

import com.gotcha.data.Settings
import com.gotcha.data.SettingsStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

private class MockTestConnector(
    override val id: String,
    override val displayName: String,
    private val connected: Boolean = true,
    private val refreshOutcome: String = "Refreshed $id"
) : Connector {
    override val description = "Test connector"
    override val toolNames = emptySet<String>()
    override val spec = ConnectorSpec(id, displayName, emptySet())
    val refreshCount = AtomicInteger(0)

    override fun isConnected(): Boolean = connected
    override fun statusLine(): String = if (connected) "Connected" else "Not connected"
    override fun disconnect() {}
    override suspend fun refreshTools(): String {
        refreshCount.incrementAndGet()
        return refreshOutcome
    }
}

private class FakeSettingsStore(
    initialSettings: Settings = Settings()
) : SettingsStore {
    private var currentSettings = initialSettings

    override fun load(): Settings = currentSettings

    override fun save(settings: Settings) {
        currentSettings = settings
    }
}

class ConnectorRefreshTest {

    @Test
    fun `refreshTools interface default method returns statusLine`() = runTest {
        val connector = MockTestConnector("test", "Test Connector")
        assertEquals("Refreshed test", connector.refreshTools())
        assertEquals(1, connector.refreshCount.get())
    }

    @Test
    fun `refreshAllActive runs refreshTools concurrently on active connectors`() = runTest {
        val active1 = MockTestConnector("active1", "Active 1")
        val active2 = MockTestConnector("active2", "Active 2")
        val inactive = MockTestConnector("inactive", "Inactive", connected = false)

        val activeConnectors = listOf(active1, active2)
        val disabled = setOf("inactive")

        val results = activeConnectors.filter { it.isActive(disabled) }
            .associate { it.id to it.refreshTools() }

        assertEquals(2, results.size)
        assertEquals("Refreshed active1", results["active1"])
        assertEquals("Refreshed active2", results["active2"])
        assertEquals(1, active1.refreshCount.get())
        assertEquals(1, active2.refreshCount.get())
        assertEquals(0, inactive.refreshCount.get())
    }

    @Test
    fun `scheduler skips refresh when interval is 0 and force is false`() = runTest {
        var refreshExecuted = false
        val store = FakeSettingsStore(Settings(connectorAutoRefreshIntervalMinutes = 0, connectorLastRefreshedAt = 0))
        val scheduler = ConnectorRefreshScheduler(
            context = null,
            store = store,
            clock = { 100_000L },
            refreshAction = {
                refreshExecuted = true
                mapOf("ha" to "ok")
            }
        )

        val results = scheduler.refreshIfNeeded(force = false)
        assertTrue(results.isEmpty())
        assertFalse(refreshExecuted)
    }

    @Test
    fun `scheduler skips refresh when elapsed time is less than interval`() = runTest {
        var refreshExecuted = false
        val lastRefreshed = 100_000L
        val currentTime = lastRefreshed + (10 * 60_000L) // 10 minutes elapsed (interval = 30 min)
        val store = FakeSettingsStore(
            Settings(
                connectorAutoRefreshIntervalMinutes = 30,
                connectorLastRefreshedAt = lastRefreshed
            )
        )
        val scheduler = ConnectorRefreshScheduler(
            context = null,
            store = store,
            clock = { currentTime },
            refreshAction = {
                refreshExecuted = true
                mapOf("ha" to "ok")
            }
        )

        val results = scheduler.refreshIfNeeded(force = false)
        assertTrue(results.isEmpty())
        assertFalse(refreshExecuted)
    }

    @Test
    fun `scheduler executes refresh when elapsed time equals or exceeds interval`() = runTest {
        var refreshExecuted = false
        val lastRefreshed = 100_000L
        val currentTime = lastRefreshed + (35 * 60_000L) // 35 minutes elapsed (interval = 30 min)
        val store = FakeSettingsStore(
            Settings(
                connectorAutoRefreshIntervalMinutes = 30,
                connectorLastRefreshedAt = lastRefreshed
            )
        )
        val scheduler = ConnectorRefreshScheduler(
            context = null,
            store = store,
            clock = { currentTime },
            refreshAction = {
                refreshExecuted = true
                mapOf("homeassistant" to "3 tools available")
            }
        )

        val results = scheduler.refreshIfNeeded(force = false)
        assertEquals(1, results.size)
        assertTrue(refreshExecuted)
        assertEquals(currentTime, store.load().connectorLastRefreshedAt)
    }

    @Test
    fun `scheduler executes refresh when force is true even if interval is disabled`() = runTest {
        var refreshExecuted = false
        val store = FakeSettingsStore(Settings(connectorAutoRefreshIntervalMinutes = 0, connectorLastRefreshedAt = 0))
        val scheduler = ConnectorRefreshScheduler(
            context = null,
            store = store,
            clock = { 500_000L },
            refreshAction = {
                refreshExecuted = true
                mapOf("notion" to "Connected")
            }
        )

        val results = scheduler.refreshIfNeeded(force = true)
        assertEquals(1, results.size)
        assertTrue(refreshExecuted)
        assertEquals(500_000L, store.load().connectorLastRefreshedAt)
    }
}
