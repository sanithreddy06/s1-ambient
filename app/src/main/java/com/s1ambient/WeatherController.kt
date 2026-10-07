package com.s1ambient

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import kotlin.math.roundToInt

internal class WeatherController(context: Context, private val display: (String, String, String) -> Unit) {
    private val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var active = false
    private var closed = false
    private var loading = false
    private val refresh = Runnable { fetch() }

    fun start() {
        active = true
        showCached()
        val age = System.currentTimeMillis() - prefs.getLong("attempt", 0)
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, if (age in 0 until INTERVAL) INTERVAL - age else 0)
    }

    fun stop() {
        active = false
        handler.removeCallbacks(refresh)
    }

    fun close() {
        stop()
        closed = true
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
            display("—", "Weather unavailable", "MS PALYA · OPEN-METEO")
        } else {
            val stale = prefs.getBoolean("failed", false) ||
                System.currentTimeMillis() - prefs.getLong("success", 0) >= INTERVAL
            display("${json.getDouble("temperature").roundToInt()}°C", condition(json.getInt("code")),
                if (stale) "LAST KNOWN · OPEN-METEO" else "MS PALYA · OPEN-METEO")
        }
    }

    private fun fetch() {
        if (!active || loading) return
        loading = true
        prefs.edit().putLong("attempt", System.currentTimeMillis()).apply()
        worker.execute {
            val result = runCatching {
                val connection = URL(ENDPOINT).openConnection() as HttpsURLConnection
                try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    check(connection.responseCode == 200)
                    val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                    val current = json.getJSONObject("current")
                    val temperature = current.getDouble("temperature_2m")
                    require(temperature.isFinite() && temperature in -100.0..70.0)
                    JSONObject().put("temperature", temperature).put("code", current.getInt("weather_code"))
                } finally { connection.disconnect() }
            }
            handler.post {
                loading = false
                if (!closed) {
                    result.onSuccess {
                        prefs.edit().putString("reading", it.toString())
                            .putLong("success", System.currentTimeMillis()).putBoolean("failed", false).apply()
                    }.onFailure { prefs.edit().putBoolean("failed", true).apply() }
                    if (active) {
                        showCached()
                        handler.removeCallbacks(refresh)
                        handler.postDelayed(refresh, INTERVAL)
                    }
                }
            }
        }
    }

    companion object {
        const val INTERVAL = 20 * 60 * 1000L
        // MS Palya bus stop, Bengaluru (OpenStreetMap node 2447233133).
        const val ENDPOINT = "https://api.open-meteo.com/v1/forecast?latitude=13.08166&longitude=77.54823&current=temperature_2m,weather_code&temperature_unit=celsius&timezone=Asia%2FKolkata"
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
