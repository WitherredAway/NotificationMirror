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
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 1500L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        val notifKey = intent.getStringExtra(NotificationHandler.EXTRA_NOTIF_KEY) ?: return
        val notifId = intent.getIntExtra(NotificationHandler.EXTRA_NOTIFICATION_ID, -1)
        val actionIndex = intent.getIntExtra(NotificationHandler.EXTRA_ACTION_INDEX, 0)
        val isMessaging = intent.getBooleanExtra(NotificationHandler.EXTRA_IS_MESSAGING, false)

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
                var sent = false
                var lastError: Exception? = null

                for (attempt in 0..MAX_RETRIES) {
                    try {
                        if (attempt > 0) {
                            Log.d(TAG, "Retry attempt $attempt for reply to $notifKey")
                            kotlinx.coroutines.delay(RETRY_DELAY_MS)
                        }

                        val nodeClient = Wearable.getNodeClient(context)
                        val nodes = nodeClient.connectedNodes.await()

                        if (nodes.isEmpty()) {
                            Log.w(TAG, "No connected nodes — phone may be out of range")
                            lastError = Exception("Phone not connected")
                            continue
                        }

                        for (node in nodes) {
                            Wearable.getMessageClient(context)
                                .sendMessage(node.id, "/reply", json.toString().toByteArray())
                                .await()
                            Log.d(TAG, "Reply sent to phone via node: ${node.displayName}")
                        }

                        sent = true
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send reply (attempt $attempt)", e)
                        lastError = e
                    }
                }

                if (sent) {
                    if (notifId >= 0) {
                        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        if (isMessaging) {
                            // Messaging/conversation notification: re-post to clear the
                            // RemoteInput spinner but keep it open so the user's reply
                            // can appear in the conversation
                            val existing = nm.activeNotifications.find { it.id == notifId }
                            if (existing != null) {
                                // Set FLAG_ONLY_ALERT_ONCE to prevent re-alerting (sound/vibration)
                                existing.notification.flags = existing.notification.flags or android.app.Notification.FLAG_ONLY_ALERT_ONCE
                                nm.notify(notifId, existing.notification)
                            }
                        } else {
                            // Non-conversation notification: dismiss after reply
                            nm.cancel(notifId)
                        }
                    }
                }

                if (!sent) {
                    Handler(Looper.getMainLooper()).post {
                        val msg = if (lastError?.message?.contains("not connected") == true) {
                            "Phone not connected — reply not sent"
                        } else {
                            "Failed to send reply"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
