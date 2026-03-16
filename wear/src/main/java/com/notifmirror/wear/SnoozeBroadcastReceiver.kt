package com.notifmirror.wear

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class SnoozeBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotifMirrorSnooze"
        const val EXTRA_NOTIF_KEY = "extra_snooze_notif_key"
        const val EXTRA_NOTIFICATION_ID = "extra_snooze_notification_id"
        const val EXTRA_SNOOZE_DURATION_MS = "extra_snooze_duration_ms"
        const val EXTRA_PACKAGE_NAME = "extra_snooze_package"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val notifKey = intent.getStringExtra(EXTRA_NOTIF_KEY) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val durationMs = intent.getLongExtra(EXTRA_SNOOZE_DURATION_MS, 300000L)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""

        Log.d(TAG, "Snooze requested for $notifKey, duration ${durationMs}ms")

        // Dismiss the notification on watch
        if (notifId >= 0) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId)
        }

        // Send snooze request to phone
        val json = JSONObject().apply {
            put("key", notifKey)
            put("durationMs", durationMs)
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodeClient = Wearable.getNodeClient(context)
                val nodes = nodeClient.connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, "/snooze", json.toString().toByteArray())
                        .await()
                }
                Log.d(TAG, "Snooze request sent to phone for $notifKey")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send snooze request", e)
            } finally {
                pendingResult.finish()
            }
        }

        val minutes = durationMs / 60000
        Toast.makeText(context, "Snoozed for ${minutes}min", Toast.LENGTH_SHORT).show()
    }
}
