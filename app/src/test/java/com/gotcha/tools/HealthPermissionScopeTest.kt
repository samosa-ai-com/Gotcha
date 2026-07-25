package com.gotcha.tools

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Health Connect throws a SecurityException when an aggregate names a metric
 * the user has not granted, so the summary request must be built from the
 * grants actually held — a partial grant has to still produce a summary.
 */
class HealthPermissionScopeTest {

    private fun read(type: kotlin.reflect.KClass<out androidx.health.connect.client.records.Record>) =
        HealthPermission.getReadPermission(type)

    @Test
    fun `no grants means no metrics`() {
        assertTrue(HealthTool.summaryMetricsFor(emptySet()).isEmpty())
    }

    @Test
    fun `a steps-only grant asks only for the steps metric`() {
        val metrics = HealthTool.summaryMetricsFor(setOf(read(StepsRecord::class)))
        assertEquals(setOf(StepsRecord.COUNT_TOTAL), metrics)
    }

    @Test
    fun `heart rate contributes both its average and peak`() {
        val metrics = HealthTool.summaryMetricsFor(setOf(read(HeartRateRecord::class)))
        assertEquals(setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX), metrics)
    }

    @Test
    fun `a partial grant mixes only the granted types`() {
        val metrics = HealthTool.summaryMetricsFor(
            setOf(read(StepsRecord::class), read(WeightRecord::class))
        )
        assertEquals(setOf(StepsRecord.COUNT_TOTAL, WeightRecord.WEIGHT_AVG), metrics)
    }

    @Test
    fun `workouts alone yield nothing to aggregate`() {
        // ExerciseSessionRecord has no summary metric; getSummary treats this as
        // "nothing to show" rather than issuing an empty aggregate request.
        assertTrue(HealthTool.summaryMetricsFor(setOf(read(ExerciseSessionRecord::class))).isEmpty())
    }

    @Test
    fun `granting everything covers every summary metric`() {
        assertEquals(8, HealthTool.summaryMetricsFor(HealthTool.PERMISSIONS).size)
    }
}
