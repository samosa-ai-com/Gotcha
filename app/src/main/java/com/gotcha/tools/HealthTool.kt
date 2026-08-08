package com.gotcha.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant

/**
 * Read-only access to the device's Health Connect store (steps, distance,
 * calories, sleep, heart rate, weight, workouts).
 *
 * Not a connector: Health Connect is on-device, needs no credentials and no
 * network. It does need its own `android.permission.health.*` grants, which are
 * requested through the existing special-access mechanism via
 * [ToolResult.HEALTH_CONNECT].
 *
 * Deliberately read-only in v1 — the agent never writes to the user's health
 * record.
 */
class HealthTool(private val context: Context) {

    companion object {
        /** Every permission the two tools can need; requested as one batch. */
        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class)
        )

        /**
         * The summary metrics each read permission unlocks. Health Connect
         * throws a SecurityException if an [AggregateRequest] names a metric
         * whose permission is missing, so the request has to be assembled from
         * the grants the user actually gave rather than from the full set.
         */
        private val SUMMARY_METRICS: Map<String, Set<AggregateMetric<*>>> = mapOf(
            HealthPermission.getReadPermission(StepsRecord::class) to
                setOf(StepsRecord.COUNT_TOTAL),
            HealthPermission.getReadPermission(DistanceRecord::class) to
                setOf(DistanceRecord.DISTANCE_TOTAL),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class) to
                setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
            HealthPermission.getReadPermission(SleepSessionRecord::class) to
                setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL),
            HealthPermission.getReadPermission(HeartRateRecord::class) to
                setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class) to
                setOf(RestingHeartRateRecord.BPM_AVG),
            HealthPermission.getReadPermission(WeightRecord::class) to
                setOf(WeightRecord.WEIGHT_AVG)
        )

        /** Metrics safe to aggregate given [granted]; empty if none of them are. */
        fun summaryMetricsFor(granted: Set<String>): Set<AggregateMetric<*>> =
            SUMMARY_METRICS.filterKeys { it in granted }.values.flatten().toSet()

        private const val PLAY_LISTING = "Health Connect"
        private const val MAX_RECORDS = 50

        /** Record types [readRecords] accepts, and the arg value that selects each. */
        private val RECORD_TYPES = mapOf(
            "steps" to StepsRecord::class,
            "distance" to DistanceRecord::class,
            "calories" to ActiveCaloriesBurnedRecord::class,
            "sleep" to SleepSessionRecord::class,
            "heart_rate" to HeartRateRecord::class,
            "resting_heart_rate" to RestingHeartRateRecord::class,
            "weight" to WeightRecord::class,
            "exercise" to ExerciseSessionRecord::class
        )

        val RECORD_TYPE_NAMES: List<String> = RECORD_TYPES.keys.toList()

        /** Android 14+ platform action for Health Connect's per-app permission manager. */
        const val ACTION_MANAGE_HEALTH_PERMISSIONS =
            "android.health.connect.action.MANAGE_HEALTH_PERMISSIONS"

        internal const val PLAY_LISTING_URI = "market://details?id=com.google.android.apps.healthdata"
        internal const val CONTROLLER_PACKAGE = "com.google.android.healthconnect.controller"
        internal const val LEGACY_PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
    }

    private fun clientOrNull(): HealthConnectClient? =
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
        } else {
            null
        }

    private fun unavailable(): ToolResult = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> ToolResult.error(
            "$PLAY_LISTING is installed but out of date on this device. Ask the user to update " +
                "it from the Play Store, then try again."
        )
        else -> ToolResult.error(
            "$PLAY_LISTING is not available on this device. On Android 13 and below it is a " +
                "separate app the user must install from the Play Store; on Android 14+ it is " +
                "built in and may be disabled."
        )
    }

    /** [type] names the specific data type that was missing, when one is known. */
    private fun needsPermission(type: String? = null): ToolResult = ToolResult.permissionNeeded(
        ToolResult.HEALTH_CONNECT,
        if (type.isNullOrBlank()) {
            "Reading health data needs Health Connect permissions, which have not been granted. " +
                "I have opened the permission screen — please allow the data types you're happy " +
                "to share and ask again."
        } else {
            "Reading '$type' needs its own Health Connect permission, which has not been " +
                "granted. I have opened the permission screen — please allow '$type' there and " +
                "ask again, or ask about a data type you have already shared."
        }
    )

    /** The subset of [PERMISSIONS] the user has actually granted; empty means none. */
    private suspend fun grantedPermissions(client: HealthConnectClient): Set<String> {
        val granted = client.permissionController.getGrantedPermissions()
            .filterTo(mutableSetOf()) { it in PERMISSIONS }
        // Keep the Settings permission row in sync — it can only read a cached value.
        HealthPermissionState.set(granted.isNotEmpty())
        return granted
    }

    /** Aggregated overview of the last N days. */
    @Suppress("ReturnCount")
    suspend fun getSummary(daysArg: Int?): ToolResult {
        val client = clientOrNull() ?: return unavailable()
        return try {
            val granted = grantedPermissions(client)
            // Workouts have no summary metric, so a grant covering only those
            // leaves nothing to aggregate — treat that like no grant at all.
            val metrics = summaryMetricsFor(granted)
            if (metrics.isEmpty()) return needsPermission()
            val days = HealthFormat.days(daysArg)
            val (start, end) = HealthFormat.window(Instant.now(), days)
            val filter = TimeRangeFilter.between(start, end)

            val aggregate: AggregationResult = client.aggregate(
                AggregateRequest(metrics = metrics, timeRangeFilter = filter)
            )

            val steps = aggregate[StepsRecord.COUNT_TOTAL]
            val sleep = aggregate[SleepSessionRecord.SLEEP_DURATION_TOTAL]
            val entries = listOf(
                "Steps" to steps?.let { "${HealthFormat.steps(it)} total (${HealthFormat.perDay(it, days)})" },
                "Distance" to aggregate[DistanceRecord.DISTANCE_TOTAL]
                    ?.let { HealthFormat.metresAsKm(it.inMeters) },
                "Active calories" to aggregate[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]
                    ?.let { "${it.inKilocalories.toLong()} kcal" },
                "Sleep" to sleep?.let {
                    "${HealthFormat.duration(it)} total (${HealthFormat.duration(it.dividedBy(days.toLong()))}/night)"
                },
                "Average heart rate" to aggregate[HeartRateRecord.BPM_AVG]?.let { "$it bpm" },
                "Peak heart rate" to aggregate[HeartRateRecord.BPM_MAX]?.let { "$it bpm" },
                "Resting heart rate" to aggregate[RestingHeartRateRecord.BPM_AVG]?.let { "$it bpm" },
                "Weight" to aggregate[WeightRecord.WEIGHT_AVG]
                    ?.let { HealthFormat.kilograms(it.inKilograms) }
            )
            ToolResult.ok(HealthFormat.summary(days, entries))
        } catch (ignored: SecurityException) {
            // The user revoked a grant between the check and the read.
            needsPermission()
        } catch (e: Exception) {
            ToolResult.error("Could not read Health Connect data: ${e.message}")
        }
    }

    /** Individual records of one type, for questions the summary can't answer. */
    @Suppress("ReturnCount")
    suspend fun getRecords(type: String?, daysArg: Int?): ToolResult {
        val client = clientOrNull() ?: return unavailable()
        val recordType = RECORD_TYPES[type?.lowercase()?.trim()]
            ?: return ToolResult.error(
                "Unknown health record type '${type.orEmpty()}'. Use one of: " +
                    RECORD_TYPE_NAMES.joinToString(", ") + "."
            )
        return try {
            // Only this record type's own grant matters — asking about steps
            // must not require the user to have shared their weight.
            if (HealthPermission.getReadPermission(recordType) !in grantedPermissions(client)) {
                return needsPermission(type)
            }
            val days = HealthFormat.days(daysArg)
            val (start, end) = HealthFormat.window(Instant.now(), days)
            val records = client.readRecords(
                ReadRecordsRequest(
                    recordType = recordType,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageSize = MAX_RECORDS
                )
            ).records

            if (records.isEmpty()) {
                return ToolResult.ok("No '$type' records in the last $days day(s).")
            }
            val rows = records.joinToString("\n") { "  " + describe(it) }
            ToolResult.ok("${records.size} '$type' record(s) in the last $days day(s):\n$rows")
        } catch (ignored: SecurityException) {
            // The user revoked a grant between the check and the read.
            needsPermission()
        } catch (e: Exception) {
            ToolResult.error("Could not read Health Connect records: ${e.message}")
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun describe(record: Record): String = when (record) {
        is StepsRecord ->
            "${HealthFormat.day(record.startTime)}: ${HealthFormat.steps(record.count)} steps"
        is DistanceRecord ->
            "${HealthFormat.day(record.startTime)}: ${HealthFormat.metresAsKm(record.distance.inMeters)}"
        is ActiveCaloriesBurnedRecord ->
            "${HealthFormat.day(record.startTime)}: ${record.energy.inKilocalories.toLong()} kcal"
        is SleepSessionRecord ->
            "${HealthFormat.minute(record.startTime)} → ${HealthFormat.minute(record.endTime)} " +
                "(${HealthFormat.duration(Duration.between(record.startTime, record.endTime))})"
        is HeartRateRecord -> {
            val samples = record.samples
            val avg = samples.map { it.beatsPerMinute }.average().toLong()
            "${HealthFormat.minute(record.startTime)}: $avg bpm average over ${samples.size} sample(s)"
        }
        is RestingHeartRateRecord ->
            "${HealthFormat.day(record.time)}: ${record.beatsPerMinute} bpm resting"
        is WeightRecord ->
            "${HealthFormat.day(record.time)}: ${HealthFormat.kilograms(record.weight.inKilograms)}"
        is ExerciseSessionRecord ->
            "${HealthFormat.minute(record.startTime)}: ${record.title ?: "workout"} " +
                "(${HealthFormat.duration(Duration.between(record.startTime, record.endTime))})"
        else -> record.javaClass.simpleName
    }
}

/**
 * The Health Connect permission intent to fire, or the best fallback that can
 * actually be resolved on this device. Never null when the Play Store is
 * reachable and never throws.
 *
 * On API 34+ the SDK's `PermissionController` contract builds its intent from
 * `ActivityResultContracts.RequestMultiplePermissions`, which stock Android
 * routes to the Health Connect screen but some OEM builds do not resolve at
 * all — firing it blind throws `ActivityNotFoundException` (the crash this
 * guards against). Each candidate is therefore only used when it has a
 * resolver, and the chain degrades to the Health Connect app itself rather
 * than a crash. Shared by the Settings permission row and the agent path.
 */
fun healthConnectPermissionIntent(context: Context): Intent? {
    if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
        // No provider (or one that is out of date): steer to the Play listing
        // instead of crash-launching a screen nothing can handle.
        return Intent(Intent.ACTION_VIEW, Uri.parse(HealthTool.PLAY_LISTING_URI))
    }
    val pm = context.packageManager
    // Preferred: the SDK's permission-request screen (parses the granted set on
    // the way back). Only used when something actually resolves it.
    runCatching {
        PermissionController.createRequestPermissionResultContract()
            .createIntent(context, HealthTool.PERMISSIONS)
    }.getOrNull()?.let { intent ->
        if (intent.resolveActivity(pm) != null) return intent
    }
    // Fallback: the Health Connect home screen. This is the reliable opener on
    // OEM builds whose per-app permission page refuses to show (launches and
    // immediately finishes) for an app with no grants yet — the home always
    // opens, and the user reaches App permissions from there.
    Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
        .takeIf { it.resolveActivity(pm) != null }
        ?.let { return it }
    // Fallback: Health Connect's per-app permission manager (Android 14+), for
    // devices where it does show the target app directly.
    healthConnectManagePermissionsIntent(context)
        .takeIf { it.resolveActivity(pm) != null }
        ?.let { return it }
    // Last resort: open the Health Connect app so the user can grant from there.
    pm.getLaunchIntentForPackage(HealthTool.CONTROLLER_PACKAGE)?.let { return it }
    pm.getLaunchIntentForPackage(HealthTool.LEGACY_PROVIDER_PACKAGE)?.let { return it }
    return null
}

/** The per-app Health Connect permission manager intent (Android 14+ platform action). */
internal fun healthConnectManagePermissionsIntent(context: Context): Intent =
    Intent(HealthTool.ACTION_MANAGE_HEALTH_PERMISSIONS)
        .putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
