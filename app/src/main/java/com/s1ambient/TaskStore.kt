package com.s1ambient

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.util.UUID

internal data class LocalTask(val id: String, val title: String, val due: LocalDateTime, var completed: Boolean) {
    fun overdue(now: LocalDateTime) = !completed && !now.isBefore(due)
}

internal class TaskStore(context: Context) {
    private val prefs = context.getSharedPreferences("tasks", Context.MODE_PRIVATE)
    var onChanged: () -> Unit = {}
    val tasks = mutableListOf<LocalTask>()
    init {
        val array = runCatching { JSONArray(prefs.getString("items", "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until array.length()) {
            runCatching {
                val item = array.getJSONObject(i)
                LocalTask(item.getString("id"), item.getString("title"),
                    LocalDateTime.parse(item.getString("due")), item.getBoolean("completed"))
            }.getOrNull()?.let(tasks::add)
        }
    }
    fun add(title: String, due: LocalDateTime) {
        tasks.add(LocalTask(UUID.randomUUID().toString(), title, due, false))
        save()
    }
    fun save() {
        val array = JSONArray()
        tasks.forEach { array.put(JSONObject().put("id", it.id).put("title", it.title)
            .put("due", it.due.toString()).put("completed", it.completed)) }
        prefs.edit().putString("items", array.toString()).apply()
        onChanged()
    }
}
