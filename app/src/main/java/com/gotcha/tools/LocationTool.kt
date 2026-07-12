package com.gotcha.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocationTool(private val context: Context) {

    fun getLocation(fresh: Boolean? = null): ToolResult {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            return ToolResult.permissionNeeded(
                Manifest.permission.ACCESS_FINE_LOCATION,
                "The Location permission is not granted. Go to Settings → Permissions → Location and enable it, then ask again."
            )
        }
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val hasFine = fine == PackageManager.PERMISSION_GRANTED

            val fix = if (fresh == true) {
                requestFreshFix(lm, hasFine)
            } else {
                getBestLastKnown(lm)
            }

            if (fix == null) {
                val hint = if (fresh == true) {
                    "Could not get a fresh GPS fix. Make sure location is enabled and you're outdoors or near a window."
                } else {
                    "No recent location fix is available. Try again with fresh=true to request a new GPS fix."
                }
                return ToolResult.ok(hint)
            }

            val lat = fix.latitude
            val lng = fix.longitude
            val address = reverseGeocode(lat, lng)
            val accuracy = if (fix.hasAccuracy()) " (±${fix.accuracy.toInt()}m)" else ""
            val altitude = if (fix.hasAltitude()) ", alt ${fix.altitude.toInt()}m" else ""
            val bearing = if (fix.hasBearing()) ", bearing ${fix.bearing.toInt()}°" else ""
            val speed = if (fix.hasSpeed()) ", speed ${"%.1f".format(fix.speed * 3.6)} km/h" else ""
            val source = if (fresh == true) " (fresh fix)" else ""

            val mapLink = "https://maps.google.com/maps?q=$lat,$lng"
            val where = if (address != null) " near $address" else ""

            ToolResult.ok(
                "Location: %.5f, %.5f%s%s%s%s%s%s\nMap: $mapLink".format(
                    lat, lng, accuracy, altitude, bearing, speed, where, source
                )
            )
        } catch (e: Exception) {
            ToolResult.error("Could not read your location: ${e.message}")
        }
    }

    private fun getBestLastKnown(lm: LocationManager): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        var best: Location? = null
        for (p in providers) {
            if (!lm.allProviders.contains(p)) continue
            val loc = try {
                lm.getLastKnownLocation(p)
            } catch (_: SecurityException) { null }
            val current = best
            if (loc != null && (current == null || loc.time > current.time || loc.accuracy < current.accuracy)) best = loc
        }
        return best
    }

    private fun requestFreshFix(lm: LocationManager, fine: Boolean): Location? {
        val latch = CountDownLatch(1)
        var result: Location? = null

        val provider = if (fine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            LocationManager.GPS_PROVIDER
        } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            LocationManager.NETWORK_PROVIDER
        } else {
            return null
        }

        @Suppress("DEPRECATION")
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                result = location
                latch.countDown()
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        latch.await(8, TimeUnit.SECONDS)
        try { lm.removeUpdates(listener) } catch (_: Exception) {}

        return result
    }

    private fun reverseGeocode(lat: Double, lng: Double): String? {
        return try {
            @Suppress("DEPRECATION")
            val results = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lng, 1)
            results?.firstOrNull()?.getAddressLine(0)
        } catch (_: Exception) { null }
    }
}
