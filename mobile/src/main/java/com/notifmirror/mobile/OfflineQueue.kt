package com.notifmirror.mobile

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Queues notification JSON payloads when the watch is disconnected.
 * On reconnect, queued notifications are sent in order.
 * Data is encrypted at rest using CryptoHelper (consistent with NotificationLog).
 */
class OfflineQueue(private val context: Context) {

    companion object {
        private const val TAG = "NotifMirrorQueue"
        private const val PREFS_NAME = "notif_mirror_offline_queue"
        private const val KEY_QUEUE = "queued_notifications"
        private const val KEY_QUEUE_ENCRYPTED = "queued_notifications_encrypted"
        private const val MAX_QUEUE_SIZE = 50
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun enqueue(notificationJson: JSONObject) {
        val queue = getQueue()
        // Copy to avoid mutating the caller's object and preserve original queuedAt on re-enqueue
        val copy = JSONObject(notificationJson.toString())
        if (!copy.has("queuedAt")) {
            copy.put("queuedAt", System.currentTimeMillis())
        }
        queue.put(copy)

        // Trim to max size (drop oldest)
        while (queue.length() > MAX_QUEUE_SIZE) {
            queue.remove(0)
        }

        saveQueue(queue)
        Log.d(TAG, "Enqueued notification, queue size: ${queue.length()}")
    }

    fun dequeueAll(): List<JSONObject> {
        val queue = getQueue()
        val list = mutableListOf<JSONObject>()
        for (i in 0 until queue.length()) {
            list.add(queue.getJSONObject(i))
        }
        clear()
        Log.d(TAG, "Dequeued ${list.size} notifications")
        return list
    }

    fun isEmpty(): Boolean = getQueue().length() == 0

    fun size(): Int = getQueue().length()

    fun clear() {
        prefs.edit()
            .remove(KEY_QUEUE)
            .remove(KEY_QUEUE_ENCRYPTED)
            .apply()
    }

    private fun saveQueue(queue: JSONArray) {
        val json = queue.toString()
        try {
            val encrypted = CryptoHelper.encryptString(json, context)
            prefs.edit().putString(KEY_QUEUE_ENCRYPTED, encrypted).apply()
        } catch (_: Exception) {
            // Fallback to plaintext if encryption fails
            prefs.edit().putString(KEY_QUEUE, json).apply()
        }
    }

    private fun getQueue(): JSONArray {
        // Try encrypted first
        val encrypted = prefs.getString(KEY_QUEUE_ENCRYPTED, null)
        if (encrypted != null) {
            try {
                val decrypted = CryptoHelper.decryptString(encrypted, context)
                return JSONArray(decrypted)
            } catch (_: Exception) { }
        }
        // Fallback to plaintext (legacy or encryption failure)
        val raw = prefs.getString(KEY_QUEUE, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            // Migrate plaintext to encrypted — verify round-trip before removing plaintext
            if (arr.length() > 0) {
                saveQueue(arr)
                val verifyEncrypted = prefs.getString(KEY_QUEUE_ENCRYPTED, null)
                if (verifyEncrypted != null) {
                    try {
                        val verifyDecrypted = CryptoHelper.decryptString(verifyEncrypted, context)
                        JSONArray(verifyDecrypted) // verify it parses
                        prefs.edit().remove(KEY_QUEUE).apply()
                    } catch (_: Exception) { /* keep plaintext */ }
                }
            }
            arr
        } catch (_: Exception) {
            JSONArray()
        }
    }
}
