package com.s1ambient

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.os.Build
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var state: AmbientState
    private lateinit var controls: ControlPanel
    private val importer = Executors.newSingleThreadExecutor()
    private val changed: () -> Unit = { tasks.refresh(); controls.refresh() }
    private val handler = Handler(Looper.getMainLooper())
    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val dayFormat = DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())
    private val dateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())
    private lateinit var root: LinearLayout
    private lateinit var clock: TextView
    private lateinit var day: TextView
    private lateinit var date: TextView
    private lateinit var weatherSource: TextView
    private lateinit var temperature: TextView
    private lateinit var condition: TextView
    private lateinit var weather: WeatherController
    private lateinit var tasks: TaskPanel
    private var taskMinute: Long = -1
    private val primary = mutableListOf<TextView>()
    private val secondary = mutableListOf<TextView>()
    private val muted = mutableListOf<TextView>()
    private var displayedDate: LocalDate? = null
    private var nightMode: Boolean? = null

    private val tick = object : Runnable {
        override fun run() {
            updateTime()
            // Align to wall-clock seconds without accumulating callback drift.
            handler.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        state = (application as AmbientApp).state
        buildDashboard()
        weather = WeatherController(this) { value, summary, source ->
            temperature.text = value
            condition.text = summary
            weatherSource.text = source
        }
        updateTime()
        hideSystemUi()
        if (state.remoteEnabled || state.ringing.isNotEmpty()) startForegroundService(Intent(this, AmbientService::class.java))
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 12)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(tick)
        state.listeners.add(changed)
        state.reschedule()
        tick.run()
        controls.refresh()
        weather.start()
        tasks.refresh()
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        weather.stop()
        state.listeners.remove(changed)
        super.onPause()
    }

    override fun onDestroy() {
        weather.close()
        tasks.close()
        controls.close()
        importer.shutdown()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun buildDashboard() {
        val width = resources.configuration.screenWidthDp.toFloat()
        val height = resources.configuration.screenHeightDp.toFloat()
        val scale = minOf(width / 1000f, height / 625f).coerceIn(0.5f, 1.4f)
        fun dp(value: Float) = (value * scale * resources.displayMetrics.density).toInt()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32f), dp(20f), dp(32f), dp(20f))
        }
        fun label(value: String, size: Float, gap: Float, group: MutableList<TextView>): TextView {
            return TextView(this).apply {
                text = value
                textSize = size * scale
                gravity = Gravity.CENTER
                includeFontPadding = false
                setSingleLine(true)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(gap) }
                group.add(this)
                root.addView(this)
            }
        }
        clock = label("00:00:00", 108f, 0f, primary).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
        }
        day = label("", 16f, 8f, secondary).apply { letterSpacing = 0.18f }
        date = label("", 15f, 7f, secondary)
        temperature = label("—", 34f, 20f, primary)
        condition = label("Weather unavailable", 12f, 8f, secondary).apply { letterSpacing = 0.12f }
        weatherSource = label("MS PALYA · OPEN-METEO", 9f, 7f, muted)
        tasks = TaskPanel(this, scale, state)
        root.addView(tasks.view, LinearLayout.LayoutParams(dp(620f), -2).apply {
            topMargin = dp(10f)
        })
        controls = ControlPanel(this, state) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "audio/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, 11)
        }
        root.addView(controls.view)
        setContentView(root)
    }

    @Deprecated("Framework result callback for Android 9 without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 11 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val appState = state
        val appContext = applicationContext
        Toast.makeText(this, "Importing audio…", Toast.LENGTH_SHORT).show()
        importer.execute {
            val result = runCatching {
                val temp = File(appContext.filesDir, "alarm-import.tmp")
                try {
                    appContext.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Cannot read this audio file" }
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= 20 * 1024 * 1024) { "Choose audio smaller than 20 MB" }
                                output.write(buffer, 0, count)
                            }
                            require(total > 0) { "Audio file is empty" }
                        }
                    }
                    val metadata = MediaMetadataRetriever()
                    try {
                        metadata.setDataSource(temp.absolutePath)
                        require(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes") { "Unsupported audio file" }
                    } finally { metadata.release() }
                    check(temp.renameTo(appState.soundFile())) { "Could not save imported audio" }
                } finally { temp.delete() }
            }
            handler.post {
                if (result.isSuccess) appState.useCustom(true)
                if (!isDestroyed) Toast.makeText(this, result.exceptionOrNull()?.message ?: "Alarm sound imported", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateTime() {
        val now = ZonedDateTime.now() // Read the current device timezone on every tick.
        clock.text = now.format(clockFormat)
        state.checkTimer()
        controls.tick()
        if (displayedDate != now.toLocalDate()) {
            displayedDate = now.toLocalDate()
            day.text = now.format(dayFormat).uppercase(Locale.getDefault())
            date.text = now.format(dateFormat).uppercase(Locale.getDefault())
        }
        val minute = now.toEpochSecond() / 60
        if (taskMinute != minute) {
            taskMinute = minute
            tasks.refresh()
        }
        val night = AmbientAppearance.isNight(now.hour)
        if (nightMode != night) {
            nightMode = night
            val palette = if (night) AmbientAppearance.night else AmbientAppearance.day
            root.setBackgroundColor(palette.background)
            primary.forEach { it.setTextColor(palette.primary) }
            secondary.forEach { it.setTextColor(palette.secondary) }
            muted.forEach { it.setTextColor(palette.muted) }
            tasks.theme(palette)
            controls.theme(palette)
            window.attributes = window.attributes.apply {
                screenBrightness = if (night) 0.04f else -1f
            }
        }
    }
}
