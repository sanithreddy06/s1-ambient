package com.s1ambient

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.Executors

internal data class WeatherPosition(val latitude: Double, val longitude: Double, val name: String)

/** Foreground, coarse, one-shot positioning. No GPS or background tracking permission. */
internal class WeatherLocation(private val context: Context) {
    private val prefs = context.getSharedPreferences("weather_location", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val geocoding = Executors.newSingleThreadExecutor()
    private var listener: LocationListener? = null
    private var generation = 0
    private var callback: ((WeatherPosition?) -> Unit)? = null
    private val timeout = Runnable { finish(null) }

    fun saved(): WeatherPosition? = runCatching {
        if (!prefs.contains("latitude") || !prefs.contains("longitude")) return null
        val latitude = prefs.getString("latitude", null)!!.toDouble()
        val longitude = prefs.getString("longitude", null)!!.toDouble()
        require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0)
        WeatherPosition(latitude, longitude, prefs.getString("name", null) ?: "Current location")
    }.getOrNull()

    @Suppress("DEPRECATION") // API 28 one-shot provider request, removed on fix/timeout/pause.
    fun resolve(result: (WeatherPosition?) -> Unit) {
        stop()
        val cached = saved()
        val now = System.currentTimeMillis()
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            (cached != null && now - prefs.getLong("success", 0) in 0 until REFRESH_INTERVAL) ||
            now - prefs.getLong("attempt", 0) in 0 until RETRY_INTERVAL) {
            result(cached)
            return
        }
        callback = result
        prefs.edit().putLong("attempt", now).apply()
        try {
            // A recent platform fix avoids activating a provider at all.
            val recent = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                .filter { acceptable(it) }
                .maxByOrNull { it.elapsedRealtimeNanos }
            if (recent != null) { finish(recent); return }
            if (!manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) { finish(null); return }
            listener = object : LocationListener {
                override fun onLocationChanged(location: Location) { if (acceptable(location)) finish(location) }
                override fun onProviderDisabled(provider: String) { finish(null) }
                override fun onProviderEnabled(provider: String) {}
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            handler.postDelayed(timeout, 20_000)
            manager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener!!, Looper.getMainLooper())
        } catch (_: Exception) { finish(null) }
    }

    private fun acceptable(location: Location) = location.hasAccuracy() && validFix(
        location.latitude, location.longitude, location.accuracy,
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000)

    private fun finish(location: Location?) {
        val deliver = callback ?: return
        val currentGeneration = generation
        removeListener()
        callback = null
        if (location == null) { deliver(saved()); return }
        val point = WeatherPosition(location.latitude, location.longitude, saved()?.name ?: "Current location")
        prefs.edit().putString("latitude", point.latitude.toString()).putString("longitude", point.longitude.toString())
            .putLong("success", System.currentTimeMillis()).apply()
        deliver(point)
        if (!Geocoder.isPresent()) return
        geocoding.execute {
            // The older blocking API runs on its own worker, never on the dashboard or weather thread.
            @Suppress("DEPRECATION")
            val name = runCatching {
                val address = Geocoder(context, Locale.getDefault()).getFromLocation(point.latitude, point.longitude, 1)?.firstOrNull()
                listOf(address?.locality, address?.subLocality, address?.subAdminArea, address?.adminArea)
                    .firstOrNull { !it.isNullOrBlank() }
            }.getOrNull()
            handler.post {
                if (currentGeneration == generation && !name.isNullOrBlank()) {
                    prefs.edit().putString("name", name).apply()
                    deliver(point.copy(name = name))
                }
            }
        }
    }
    private fun removeListener() {
        handler.removeCallbacks(timeout)
        listener?.let { runCatching { manager.removeUpdates(it) } }
        listener = null
    }
    fun stop() { generation++; removeListener(); callback = null }
    fun close() { stop(); geocoding.shutdownNow() }
    companion object {
        const val REFRESH_INTERVAL = 6 * 60 * 60 * 1000L
        const val RETRY_INTERVAL = 20 * 60 * 1000L
        fun validFix(latitude: Double, longitude: Double, accuracy: Float, ageMillis: Long) =
            latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            accuracy in 0f..10_000f && ageMillis in 0..30 * 60 * 1000L
    }
}
