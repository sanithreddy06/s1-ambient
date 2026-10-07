package com.s1ambient

/** Monotonic milliseconds supplied by the caller; no UI tick is needed for accuracy. */
internal class TimeState(
    var duration: Long = 5 * 60_000L,
    var timerRemaining: Long = duration,
    var timerStarted: Long = 0,
    var timerMode: String = "idle",
    var watchAccumulated: Long = 0,
    var watchStarted: Long = 0,
    var watchRunning: Boolean = false
) {
    fun remaining(now: Long) = if (timerMode == "running")
        (timerRemaining - (now - timerStarted).coerceAtLeast(0)).coerceAtLeast(0) else timerRemaining
    fun elapsed(now: Long) = watchAccumulated + if (watchRunning) (now - watchStarted).coerceAtLeast(0) else 0
    fun timer(action: String, now: Long, seconds: Long? = null) {
        when (action) {
            "set" -> {
                require(seconds != null && seconds in 1..86400) { "Duration must be 1–86400 seconds" }
                duration = seconds * 1000; timerRemaining = duration; timerMode = "idle"
            }
            "start" -> { timerRemaining = duration; timerStarted = now; timerMode = "running" }
            "pause" -> if (timerMode == "running") {
                timerRemaining = remaining(now); timerMode = if (timerRemaining == 0L) "finished" else "paused"
            }
            "resume" -> if (timerMode == "paused") { timerStarted = now; timerMode = "running" }
            "reset", "stop" -> { timerRemaining = duration; timerMode = "idle" }
            else -> throw IllegalArgumentException("Unknown timer action")
        }
    }
    fun finish(now: Long): Boolean {
        if (timerMode != "running" || remaining(now) > 0) return false
        timerRemaining = 0; timerMode = "finished"; return true
    }
    fun stopwatch(action: String, now: Long) {
        when (action) {
            "start", "resume" -> if (!watchRunning) { watchStarted = now; watchRunning = true }
            "pause" -> { watchAccumulated = elapsed(now); watchRunning = false }
            "reset" -> { watchAccumulated = 0; watchRunning = false }
            else -> throw IllegalArgumentException("Unknown stopwatch action")
        }
    }
}
