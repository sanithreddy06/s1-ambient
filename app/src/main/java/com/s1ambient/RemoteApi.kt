package com.s1ambient

import org.json.JSONObject

/** Called on the main thread, so phone and native UI mutations are serialized. */
internal class RemoteApi(private val state: AmbientState) {
    private val pairing = PairingGate()
    fun handle(request: HttpRequest): HttpResponse {
        fun json(value: Any) = HttpResponse.json(200, value.toString())
        fun error(code: Int, message: String) = HttpResponse.json(code, JSONObject().put("error", message).toString())
        return try {
            if (request.path == "/api/pair" && request.method == "POST") {
                val now = android.os.SystemClock.elapsedRealtime()
                val body = JSONObject(request.body)
                val code = body.getString("code").replace("-", "").trim().lowercase()
                when (pairing.pair(code, state.pairingCode, now)) {
                    429 -> return error(429, "Wait one minute before pairing again")
                    401 -> return error(401, "Pairing code does not match")
                }
                return json(JSONObject().put("token", state.accessToken))
            }
            if (!PairingGate.equal(request.headers["authorization"] ?: "", "Bearer ${state.accessToken}"))
                return error(401, "Pair this phone with the code shown on the S1")
            if (request.method == "GET") return when (request.path) {
                "/api/status" -> json(state.status())
                "/api/tasks" -> json(state.tasksJson())
                "/api/timer" -> { state.checkTimer(); json(state.timerJson()) }
                "/api/stopwatch" -> json(state.stopwatchJson())
                "/api/alarms" -> json(state.alarmsJson())
                else -> error(404, "Not found")
            }
            val body = if (request.body.isBlank()) JSONObject() else JSONObject(request.body)
            val parts = request.path.trim('/').split('/')
            when {
                request.path == "/api/tasks" && request.method == "POST" ->
                    state.addTask(body.getString("title"), body.getString("time"))
                parts.size == 3 && parts[1] == "tasks" && request.method in listOf("PUT", "DELETE") ->
                    state.taskChange(parts[2], if (request.method == "PUT") body.getBoolean("completed") else null, request.method == "DELETE")
                parts.size == 3 && parts[1] == "timer" && request.method == "POST" -> {
                    val action = parts[2]
                    state.timer(action, if (action == "set") body.getLong("seconds") else null)
                }
                parts.size == 3 && parts[1] == "stopwatch" && request.method == "POST" -> state.stopwatch(parts[2])
                request.path == "/api/alarms" && request.method == "POST" -> state.addAlarm(body.getString("time"))
                parts.size == 3 && parts[1] == "alarms" && request.method in listOf("PUT", "DELETE") ->
                    state.alarmChange(parts[2], if (request.method == "PUT") body.getBoolean("enabled") else null, request.method == "DELETE")
                request.path == "/api/alerts/dismiss" && request.method == "POST" -> state.dismiss()
                request.path == "/api/sound" && request.method == "PUT" -> state.useCustom(body.getBoolean("custom"))
                else -> return error(404, "Not found")
            }
            json(state.status())
        } catch (e: NoSuchElementException) { error(404, e.message ?: "Not found") }
          catch (e: IllegalStateException) { error(409, e.message ?: "Not available") }
          catch (e: Exception) { error(400, e.message ?: "Invalid request") }
    }
}
