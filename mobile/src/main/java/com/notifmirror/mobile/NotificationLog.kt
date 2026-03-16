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
        private const val KEY_COUNT = "log_entry_count"
        private const val ONE_WEEK_MS = 7L * 24 * 60 * 60 * 1000
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
        var entries = getEntriesRaw()
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
        entries = pruneOldEntries(entries)
        saveEntries(entries)
        // Update count based on what was saved
        prefs.edit().putInt(KEY_COUNT, entries.length()).apply()
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

    fun getCount(): Int = prefs.getInt(KEY_COUNT, -1)

    fun clear() {
        prefs.edit()
            .remove(KEY_LOG)
            .remove(KEY_LOG_ENCRYPTED)
            .putInt(KEY_COUNT, 0)
            .apply()
    }

    private fun pruneOldEntries(entries: JSONArray): JSONArray {
        val cutoff = System.currentTimeMillis() - ONE_WEEK_MS
        val pruned = JSONArray()
        for (i in 0 until entries.length()) {
            val obj = entries.getJSONObject(i)
            if (obj.optLong("time", 0) >= cutoff) {
                pruned.put(obj)
            }
        }
        return pruned
    }

    private fun saveEntries(entries: JSONArray) {
        val json = entries.toString()
        try {
            val encrypted = CryptoHelper.encryptString(json, context)
            prefs.edit()
                .putString(KEY_LOG_ENCRYPTED, encrypted)
                .remove(KEY_LOG)
                .apply()
        } catch (e: Exception) {
            // Never store plaintext — log the failure and skip saving
            android.util.Log.w("NotificationLog", "Failed to encrypt log entries, skipping save", e)
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
                // Key rotation or corrupted data — start fresh rather than silently losing data
                android.util.Log.w("NotificationLog", "Failed to decrypt log entries (key rotation?), starting fresh")
                prefs.edit().remove(KEY_LOG_ENCRYPTED).apply()
            }
        }
        // Migrate any legacy plaintext data, then remove it
        val raw = prefs.getString(KEY_LOG, null)
        if (raw != null) {
            return try {
                val arr = JSONArray(raw)
                // Migrate to encrypted and remove plaintext
                if (arr.length() > 0) {
                    saveEntries(arr)
                }
                prefs.edit().remove(KEY_LOG).apply()
                arr
            } catch (_: Exception) {
                prefs.edit().remove(KEY_LOG).apply()
                JSONArray()
            }
        }
        return JSONArray()
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
