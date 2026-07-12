package com.gotcha.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import java.util.Locale

class LocationTool(private val context: Context) {

    /** Report the last-known device location (needs ACCESS_FINE_LOCATION). */
    fun getLocation(): ToolResult {
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
                } catch (_: SecurityException) {
                    null
                }
                val current = best
                if (loc != null && (current == null || loc.time > current.time)) best = loc
            }
            val fix = best ?: return ToolResult.ok(
                "No recent location fix is available. Ask the user to open a maps app briefly, then try again."
            )
            val lat = fix.latitude
            val lng = fix.longitude
            val address = reverseGeocode(lat, lng)
            val where = if (address != null) " (near $address)" else ""
            ToolResult.ok("Location: %.5f, %.5f%s".format(lat, lng, where))
        } catch (e: Exception) {
            ToolResult.error("Could not read your location: ${e.message}")
        }
    }

    private fun reverseGeocode(lat: Double, lng: Double): String? {
        return try {
            @Suppress("DEPRECATION")
            val results = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lng, 1)
            results?.firstOrNull()?.getAddressLine(0)
        } catch (_: Exception) {
            null
        }
    }
}
