package com.gotcha.connectors

import com.gotcha.data.Settings
import com.gotcha.data.SettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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
}
