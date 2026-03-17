package com.notifmirror.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Fires when the user dismisses an ongoing/persistent notification from the watch.
 * Sends a message to the phone so it can re-send the notification if it's still active.
 */
class OngoingDismissReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotifMirrorOngoing"
        const val EXTRA_NOTIF_KEY = "extra_notif_key"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val notifKey = intent.getStringExtra(EXTRA_NOTIF_KEY) ?: return

        Log.d(TAG, "Ongoing notification dismissed from watch: $notifKey")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject().apply {
                    put("key", notifKey)
                }
                val nodeClient = Wearable.getNodeClient(context)
                val nodes = nodeClient.connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, "/resend_ongoing", json.toString().toByteArray())
                        .await()
                    Log.d(TAG, "Requested resend of ongoing notification from phone: $notifKey")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request resend of ongoing notification", e)
            }
        }
    }
}
