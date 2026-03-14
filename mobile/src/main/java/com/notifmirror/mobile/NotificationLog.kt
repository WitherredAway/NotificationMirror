package com.notifmirror.mobile

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class NotificationLog(context: Context) {

    companion object {
        private const val PREFS_NAME = "notif_mirror_log"
        private const val KEY_LOG = "log_entries"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun addEntry(
        packageName: String,
        title: String,
        text: String,
        status: String,
        detail: String = ""
    ) {
        val entries = getEntriesRaw()
        val entry = JSONObject().apply {
            put("time", System.currentTimeMillis())
            put("package", packageName)
            put("title", title)
            put("text", text)
            put("status", status)
            put("detail", detail)
        }
        entries.put(entry)
        prefs.edit().putString(KEY_LOG, entries.toString()).apply()
    }

    fun getEntries(): List<LogEntry> {
        val entries = getEntriesRaw()
        val list = mutableListOf<LogEntry>()
        for (i in entries.length() - 1 downTo 0) {
            val obj = entries.getJSONObject(i)
            list.add(
                LogEntry(
                    time = obj.getLong("time"),
                    packageName = obj.getString("package"),
                    title = obj.getString("title"),
                    text = obj.getString("text"),
                    status = obj.getString("status"),
                    detail = obj.optString("detail", "")
                )
            )
        }
        return list
    }

    fun clear() {
        prefs.edit().putString(KEY_LOG, "[]").apply()
    }

    private fun getEntriesRaw(): JSONArray {
        val raw = prefs.getString(KEY_LOG, "[]") ?: "[]"
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    data class LogEntry(
        val time: Long,
        val packageName: String,
        val title: String,
        val text: String,
        val status: String,
        val detail: String
    )
}
