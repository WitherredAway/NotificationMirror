package com.notifmirror.wear

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.RemoteInput
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Handles inline reply actions from notifications without opening an Activity.
 * This prevents the app from visually opening when the user replies via the
 * notification's RemoteInput on the watch.
 */
class ReplyBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotifMirrorReply"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        val notifKey = intent.getStringExtra(NotificationHandler.EXTRA_NOTIF_KEY) ?: return
        val notifId = intent.getIntExtra(NotificationHandler.EXTRA_NOTIFICATION_ID, -1)
        val actionIndex = intent.getIntExtra(NotificationHandler.EXTRA_ACTION_INDEX, 0)

        // Extract the reply text from RemoteInput
        val remoteInputResults = RemoteInput.getResultsFromIntent(intent)
        val replyText = remoteInputResults?.getCharSequence(NotificationHandler.KEY_REPLY)?.toString()

        if (replyText.isNullOrEmpty()) {
            Log.w(TAG, "No reply text received for $notifKey")
            return
        }

        Log.d(TAG, "Reply received for $notifKey: $replyText")

        // Mark this conversation as recently replied so the next update is silent
        NotificationHandler.markReplied(notifKey)

        val json = JSONObject().apply {
            put("key", notifKey)
            put("actionIndex", actionIndex)
            put("reply", replyText)
        }

        val pendingResult = goAsync()
        scope.launch {
            try {
                val nodeClient = Wearable.getNodeClient(context)
                val nodes = nodeClient.connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, "/reply", json.toString().toByteArray())
                        .await()
                    Log.d(TAG, "Reply sent to phone via node: ${node.displayName}")
                }

                // Dismiss the notification after reply
                if (notifId >= 0) {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(notifId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send reply", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Failed to send reply", Toast.LENGTH_SHORT).show()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
