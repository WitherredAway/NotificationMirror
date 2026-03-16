package com.notifmirror.wear

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class ActionBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotifMirrorAction"
        private const val ACTION_RESULT_TIMEOUT_MS = 5000L
        var awaitingResult = false
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        val notifKey = intent.getStringExtra(NotificationHandler.EXTRA_NOTIF_KEY) ?: return
        val notifId = intent.getIntExtra(NotificationHandler.EXTRA_NOTIFICATION_ID, -1)
        val actionIndex = intent.getIntExtra(NotificationHandler.EXTRA_ACTION_INDEX, -1)

        if (actionIndex < 0) return

        Log.d(TAG, "Action button pressed: $notifKey action $actionIndex")

        val json = JSONObject().apply {
            put("key", notifKey)
            put("actionIndex", actionIndex)
        }

        awaitingResult = true

        val pendingResult = goAsync()
        scope.launch {
            try {
                val nodeClient = Wearable.getNodeClient(context)
                val nodes = nodeClient.connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, "/action", json.toString().toByteArray())
                        .await()
                    Log.d(TAG, "Action sent to phone via node: ${node.displayName}")
                }

                // Dismiss the notification after action
                if (notifId >= 0) {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(notifId)
                }

                // Timeout: if no /action_result received in 5s, show timeout toast
                Handler(Looper.getMainLooper()).postDelayed({
                    if (awaitingResult) {
                        awaitingResult = false
                        Toast.makeText(context, "No response from phone", Toast.LENGTH_SHORT).show()
                    }
                }, ACTION_RESULT_TIMEOUT_MS)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send action to phone", e)
                awaitingResult = false
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Failed to reach phone", Toast.LENGTH_SHORT).show()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
