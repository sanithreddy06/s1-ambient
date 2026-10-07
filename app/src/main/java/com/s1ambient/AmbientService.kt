package com.s1ambient

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.net.*
import android.os.*
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** One foreground service keeps LAN access available and owns alert playback. */
class AmbientService : Service() {
    private lateinit var state: AmbientState
    private val handler = Handler(Looper.getMainLooper())
    private val binding = Executors.newSingleThreadExecutor()
    private val networks = linkedMapOf<Network, String>()
    private var server: LocalHttpServer? = null
    private var serverKey = ""
    private var generation = 0
    private var destroyed = false
    private var started = false
    private var player: MediaPlayer? = null
    private var playing = ""
    private var focus: AudioFocusRequest? = null
    private lateinit var api: RemoteApi
    private lateinit var pages: Map<String, HttpResponse>
    private val finishTimer = Runnable { state.checkTimer() }
    private val silence = Runnable { state.dismiss() }
    private val changed: () -> Unit = {
        updateServer()
        updateSound()
        handler.removeCallbacks(finishTimer)
        if (state.time.timerMode == "running") handler.postDelayed(finishTimer,
            state.time.remaining(SystemClock.elapsedRealtime()).coerceAtLeast(1))
        getSystemService(NotificationManager::class.java).notify(1, notification())
        if (started && !state.remoteEnabled && state.ringing.isEmpty()) stopSelf()
    }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            val ip = properties.linkAddresses.map { it.address }.firstOrNull {
                it is java.net.Inet4Address && it.isSiteLocalAddress && !it.isLoopbackAddress
            }?.hostAddress
            if (ip == null) networks.remove(network) else networks[network] = ip
            updateServer()
        }
        override fun onLost(network: Network) { networks.remove(network); updateServer() }
    }
    override fun onCreate() {
        super.onCreate()
        state = (application as AmbientApp).state
        api = RemoteApi(state)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("ambient", "S1 Ambient local control", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, notification())
        pages = mapOf("/" to page("remote/index.html", "text/html; charset=utf-8"),
            "/app.css" to page("remote/app.css", "text/css; charset=utf-8"),
            "/app.js" to page("remote/app.js", "text/javascript; charset=utf-8"))
        state.listeners.add(changed)
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback, handler)
        state.reschedule()
        changed()
    }
    private fun page(path: String, type: String) = HttpResponse(200, type, assets.open(path).use { it.readBytes() })
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "alert" -> if (intent.getStringExtra("id") == "timer") state.checkTimer()
                else intent.getStringExtra("id")?.let { state.fireAlarm(it, intent.getLongExtra("scheduledAt", 0)) }
            "dismiss" -> state.dismiss()
        }
        started = true
        changed()
        return if (state.remoteEnabled) START_STICKY else START_NOT_STICKY
    }
    override fun onBind(intent: Intent?) = null
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val dismiss = PendingIntent.getService(this, 1, Intent(this, AmbientService::class.java).setAction("dismiss"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "ambient").setSmallIcon(R.drawable.ic_status)
            .setContentTitle(if (state.ringing.isEmpty()) "S1 Ambient" else state.ringing)
            .setContentText(if (state.ringing.isEmpty()) state.serverInfo else "Tap Dismiss to stop the sound")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .apply { if (state.ringing.isNotEmpty()) addAction(Notification.Action.Builder(null, "Dismiss", dismiss).build()) }
            .build()
    }
    private fun updateServer() {
        if (destroyed) return
        val ip = if (state.remoteEnabled) networks.values.firstOrNull() else null
        val key = "$ip:${state.port}:${state.remoteEnabled}"
        if (serverKey == key) return
        serverKey = key
        val version = ++generation
        server?.close(); server = null
        if (ip == null) {
            state.serverStatus(if (state.remoteEnabled) "Waiting for Wi-Fi" else "Remote disabled")
            return
        }
        val port = state.port
        state.serverStatus("Starting local control…")
        binding.execute {
            val candidate = LocalHttpServer(InetAddress.getByName(ip), port, pages) { request ->
                val work = FutureTask { api.handle(request) }
                handler.post(work)
                try { work.get(3, TimeUnit.SECONDS) }
                catch (_: Exception) { work.cancel(false); HttpResponse.json(503, "{\"error\":\"S1 busy; refresh before retrying\"}") }
            }
            val result = runCatching { candidate.start() }
            handler.post {
                if (destroyed || version != generation) candidate.close()
                else if (result.isSuccess) {
                    server = candidate
                    state.serverStatus("Connected · $ip", "http://$ip:$port")
                } else {
                    candidate.close()
                    state.serverStatus("Port $port unavailable. Change port or toggle remote to retry.")
                }
            }
        }
    }
    private fun updateSound() {
        if (playing == state.ringing) return
        stopSound()
        playing = state.ringing
        if (playing.isEmpty()) return
        handler.postDelayed(silence, 10 * 60_000L)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS) state.dismiss()
            }.build()
        getSystemService(AudioManager::class.java).requestAudioFocus(focus!!)
        play(attributes, state.customSound)
    }
    private fun play(attributes: AudioAttributes, custom: Boolean) {
        try {
            val media = MediaPlayer()
            player = media
            media.setAudioAttributes(attributes)
            media.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            if (custom && state.soundFile().isFile) media.setDataSource(state.soundFile().absolutePath)
            else resources.openRawResourceFd(R.raw.s1_alert).use { media.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            media.isLooping = true
            media.setOnPreparedListener { if (player === it && state.ringing.isNotEmpty()) { it.start(); state.audioStatus("") } }
            media.setOnErrorListener { failed, _, _ ->
                if (player === failed) {
                    failed.release(); player = null
                    if (custom) play(attributes, false) else state.audioStatus("Sound unavailable; dismiss the visible alert")
                }
                true
            }
            media.prepareAsync()
        } catch (_: Exception) {
            player?.release(); player = null
            if (custom) play(attributes, false) else state.audioStatus("Sound unavailable; dismiss the visible alert")
        }
    }
    private fun stopSound() {
        handler.removeCallbacks(silence)
        player?.release(); player = null
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }; focus = null
    }
    override fun onDestroy() {
        destroyed = true
        generation++
        state.listeners.remove(changed)
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        server?.close(); binding.shutdownNow()
        handler.removeCallbacks(finishTimer)
        stopSound()
        state.serverStatus(if (state.remoteEnabled) "Remote stopped; open S1 Ambient to reconnect" else "Remote disabled")
        super.onDestroy()
    }
}
