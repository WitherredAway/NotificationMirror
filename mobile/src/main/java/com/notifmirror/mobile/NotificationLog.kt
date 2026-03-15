package com.notifmirror.mobile

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class NotificationLog(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "notif_mirror_log"
        private const val KEY_LOG = "log_entries"
        private const val KEY_LOG_ENCRYPTED = "log_entries_encrypted"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun addEntry(
        packageName: String,
        title: String,
        text: String,
        status: String,
        detail: String = "",
        notifKey: String = "",
        actionsJson: String = ""
    ) {
        val entries = getEntriesRaw()
        val entry = JSONObject().apply {
            put("time", System.currentTimeMillis())
            put("package", packageName)
            put("title", title)
            put("text", text)
            put("status", status)
            put("detail", detail)
            if (notifKey.isNotEmpty()) put("notifKey", notifKey)
            if (actionsJson.isNotEmpty()) put("actions", actionsJson)
        }
        entries.put(entry)
        saveEntries(entries)
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
                    detail = obj.optString("detail", ""),
                    notifKey = obj.optString("notifKey", ""),
                    actionsJson = obj.optString("actions", "")
                )
            )
        }
        return list
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_LOG)
            .remove(KEY_LOG_ENCRYPTED)
            .apply()
    }

    private fun saveEntries(entries: JSONArray) {
        val json = entries.toString()
        try {
            val encrypted = CryptoHelper.encryptString(json, context)
            prefs.edit()
                .putString(KEY_LOG_ENCRYPTED, encrypted)
                .remove(KEY_LOG)
                .apply()
        } catch (_: Exception) {
            // Fallback to plaintext if encryption fails
            prefs.edit().putString(KEY_LOG, json).apply()
        }
    }

    private fun getEntriesRaw(): JSONArray {
        // Try encrypted first
        val encrypted = prefs.getString(KEY_LOG_ENCRYPTED, null)
        if (encrypted != null) {
            try {
                val decrypted = CryptoHelper.decryptString(encrypted, context)
                return JSONArray(decrypted)
            } catch (_: Exception) {
                // Fall through to plaintext
            }
        }
        // Fallback to plaintext (legacy or encryption failure)
        val raw = prefs.getString(KEY_LOG, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            // Migrate plaintext to encrypted
            if (arr.length() > 0) saveEntries(arr)
            arr
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
        val detail: String,
        val notifKey: String = "",
        val actionsJson: String = ""
    )
}
