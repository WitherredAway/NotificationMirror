package com.notifmirror.mobile

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Queues notification JSON payloads when the watch is disconnected.
 * On reconnect, queued notifications are sent in order.
 */
class OfflineQueue(context: Context) {

    companion object {
        private const val TAG = "NotifMirrorQueue"
        private const val PREFS_NAME = "notif_mirror_offline_queue"
        private const val KEY_QUEUE = "queued_notifications"
        private const val MAX_QUEUE_SIZE = 50
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun enqueue(notificationJson: JSONObject) {
        val queue = getQueue()
        // Add timestamp to track when it was queued
        notificationJson.put("queuedAt", System.currentTimeMillis())
        queue.put(notificationJson)

        // Trim to max size (drop oldest)
        while (queue.length() > MAX_QUEUE_SIZE) {
            queue.remove(0)
        }

        prefs.edit().putString(KEY_QUEUE, queue.toString()).apply()
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
        prefs.edit().putString(KEY_QUEUE, "[]").apply()
    }

    private fun getQueue(): JSONArray {
        val raw = prefs.getString(KEY_QUEUE, "[]") ?: "[]"
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }
}
