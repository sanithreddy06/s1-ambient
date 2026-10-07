package com.s1ambient

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import kotlin.math.roundToInt

internal class WeatherController(context: Context, private val display: (String, String, String) -> Unit) {
    private val appContext = context.applicationContext
    private var hadPermission = false
    private val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)
    private val location = WeatherLocation(context.applicationContext)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var active = false
    private var closed = false
    private var loading = false
    private val refresh = Runnable { resolveLocation() }
    private var currentPosition: WeatherPosition? = null

    fun start() {
        active = true
        hadPermission = hasLocationPermission()
        showCached()
        handler.removeCallbacks(refresh)
        resolveLocation()
    }

    private fun hasLocationPermission() = appContext.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    fun permissionChanged() {
        if (active && hasLocationPermission() != hadPermission) start()
    }

    private fun resolveLocation() {
        if (!active) return
        location.resolve { position ->
            if (active && !closed) {
                currentPosition = position
                showCached()
                val age = System.currentTimeMillis() - prefs.getLong("attempt", 0)
                val reading = runCatching { JSONObject(prefs.getString("reading", "") ?: "") }.getOrNull()
                val newPosition = position != null && !matches(reading, position) &&
                    (prefs.getString("attemptLatitude", null) != position.latitude.toString() ||
                     prefs.getString("attemptLongitude", null) != position.longitude.toString())
                if (position != null && (newPosition || age !in 0 until INTERVAL)) fetch(position)
                else schedule(if (age in 0 until INTERVAL) INTERVAL - age else INTERVAL)
            }
        }
    }

    private fun schedule(delay: Long = INTERVAL) {
        handler.removeCallbacks(refresh)
        if (active) handler.postDelayed(refresh, delay)
    }

    fun stop() {
        active = false
        location.stop()
        handler.removeCallbacks(refresh)
    }

    fun close() {
        stop()
        closed = true
        location.close()
        worker.shutdownNow()
    }

    private fun showCached() {
        val cached = prefs.getString("reading", null)
        val json = runCatching {
            JSONObject(cached ?: "").also {
                require(it.getDouble("temperature").isFinite())
                it.getInt("code")
            }
        }.getOrNull()
        if (json == null) {
            display("—", "Weather unavailable", "${currentPosition?.name ?: location.saved()?.name ?: "Current location"} · OPEN-METEO")
        } else {
            val stale = prefs.getBoolean("failed", false) ||
                System.currentTimeMillis() - prefs.getLong("success", 0) >= INTERVAL
            val name = if (currentPosition?.let { matches(json, it) } == true) currentPosition!!.name
                else json.optString("name", "Last known weather")
            display("${json.getDouble("temperature").roundToInt()}°C", condition(json.getInt("code")),
                "$name · ${if (stale) "LAST KNOWN · " else ""}OPEN-METEO")
        }
    }

    private fun fetch(position: WeatherPosition) {
        if (!active || loading) return
        loading = true
        prefs.edit().putLong("attempt", System.currentTimeMillis())
            .putString("attemptLatitude", position.latitude.toString())
            .putString("attemptLongitude", position.longitude.toString()).apply()
        worker.execute {
            val result = runCatching {
                val connection = URL(endpoint(position.latitude, position.longitude)).openConnection() as HttpsURLConnection
                try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    check(connection.responseCode == 200)
                    val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                    val current = json.getJSONObject("current")
                    val temperature = current.getDouble("temperature_2m")
                    require(temperature.isFinite() && temperature in -100.0..70.0)
                    JSONObject().put("temperature", temperature).put("code", current.getInt("weather_code"))
                        .put("latitude", position.latitude).put("longitude", position.longitude).put("name", position.name)
                } finally { connection.disconnect() }
            }
            handler.post {
                loading = false
                if (!closed) {
                    result.onSuccess {
                        if (currentPosition?.let { point -> matches(it, point) } == true) it.put("name", currentPosition!!.name)
                        prefs.edit().putString("reading", it.toString())
                            .putLong("success", System.currentTimeMillis()).putBoolean("failed", false).apply()
                    }.onFailure { prefs.edit().putBoolean("failed", true).apply() }
                    if (active) {
                        showCached()
                        val latest = currentPosition
                        if (latest != null && (latest.latitude != position.latitude || latest.longitude != position.longitude)) fetch(latest)
                        else schedule()
                    }
                }
            }
        }
    }

    companion object {
        const val INTERVAL = 20 * 60 * 1000L
        fun endpoint(latitude: Double, longitude: Double): String {
            require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0)
            return String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f&current=temperature_2m,weather_code&temperature_unit=celsius&timezone=auto",
                latitude, longitude)
        }
        private fun matches(reading: JSONObject?, point: WeatherPosition) = reading != null &&
            reading.optDouble("latitude") == point.latitude && reading.optDouble("longitude") == point.longitude
        fun condition(code: Int) = when (code) {
            0 -> "Clear"
            1 -> "Mainly Clear"
            2 -> "Partly Cloudy"
            3 -> "Cloudy"
            45, 48 -> "Fog"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing Drizzle"
            61, 63, 80, 81 -> "Rain"
            65, 82 -> "Heavy Rain"
            66, 67 -> "Freezing Rain"
            71, 73, 75, 77, 85, 86 -> "Snow"
            95 -> "Thunderstorm"
            96, 99 -> "Thunderstorm with Hail"
            else -> "Condition unavailable"
        }
    }
}
