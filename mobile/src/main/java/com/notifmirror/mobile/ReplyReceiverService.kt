package com.notifmirror.mobile

import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class ReplyReceiverService : WearableListenerService() {

    companion object {
        private const val TAG = "NotifMirrorAction"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            "/reply" -> handleReply(messageEvent)
            "/action" -> handleAction(messageEvent)
            "/open_settings" -> handleOpenSettings()
            "/snooze" -> handleSnooze(messageEvent)
            "/request_key" -> handleKeyRequest()
        }
    }

    private fun handleOpenSettings() {
        Log.d(TAG, "Received request to open settings from watch")
        val intent = Intent(this, AppSettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun handleReply(messageEvent: MessageEvent) {
        try {
            val json = JSONObject(String(messageEvent.data))
            val notifKey = json.getString("key")
            val actionIndex = json.getInt("actionIndex")
            val replyText = json.getString("reply")

            Log.d(TAG, "Received reply for $notifKey action $actionIndex: $replyText")

            val actionKey = "$notifKey:$actionIndex"
            val action = NotificationListener.pendingActions[actionKey]
            if (action == null) {
                Log.w(TAG, "No pending action found for key: $actionKey")
                sendActionResult(false, "Notification was dismissed — action no longer available")
                return
            }

            val remoteInputs = action.remoteInputs
            if (remoteInputs == null || remoteInputs.isEmpty()) {
                Log.w(TAG, "No remote inputs found for action")
                sendActionResult(false, "Reply input not available")
                return
            }

            val intent = Intent()
            val bundle = Bundle()
            for (ri in remoteInputs) {
                bundle.putCharSequence(ri.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(action.remoteInputs, intent, bundle)
            action.actionIntent.send(this, 0, intent)

            Log.d(TAG, "Reply sent successfully for $actionKey")
            sendActionResult(true, "Reply sent")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle reply", e)
            sendActionResult(false, "Failed: ${e.message}")
        }
    }

    private fun handleAction(messageEvent: MessageEvent) {
        try {
            val json = JSONObject(String(messageEvent.data))
            val notifKey = json.getString("key")
            val actionIndex = json.getInt("actionIndex")

            Log.d(TAG, "Received action trigger for $notifKey action $actionIndex")

            val actionKey = "$notifKey:$actionIndex"
            val action = NotificationListener.pendingActions[actionKey]
            if (action == null) {
                Log.w(TAG, "No pending action found for key: $actionKey")
                sendActionResult(false, "Notification was dismissed — action no longer available")
                return
            }

            action.actionIntent.send()

            Log.d(TAG, "Action executed successfully for $actionKey")
            sendActionResult(true, "Action executed")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle action", e)
            sendActionResult(false, "Failed: ${e.message}")
        }
    }

    private fun handleSnooze(messageEvent: MessageEvent) {
        try {
            val json = JSONObject(String(messageEvent.data))
            val notifKey = json.getString("key")
            val durationMs = json.optLong("durationMs", 300000L) // default 5 min

            Log.d(TAG, "Received snooze request for $notifKey, duration ${durationMs}ms")

            // Get the NotificationListenerService instance to call snoozeNotification
            val listener = NotificationListener.instance
            if (listener != null) {
                listener.snoozeNotification(notifKey, durationMs)
                Log.d(TAG, "Snoozed notification $notifKey for ${durationMs}ms")
                sendActionResult(true, "Snoozed for ${durationMs / 60000} min")
            } else {
                Log.w(TAG, "NotificationListener not available for snooze")
                sendActionResult(false, "Notification listener not active")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle snooze", e)
            sendActionResult(false, "Snooze failed: ${e.message}")
        }
    }

    private fun handleKeyRequest() {
        Log.d(TAG, "Watch requested encryption key — re-syncing")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Try via NotificationListener instance first (it has the full sync logic)
                val listener = NotificationListener.instance
                if (listener != null) {
                    listener.resyncEncryptionKey()
                } else {
                    // Fallback: sync key directly via DataClient
                    val keyBytes = CryptoHelper.getKeyBytes(this@ReplyReceiverService)
                    val putReq = com.google.android.gms.wearable.PutDataMapRequest.create("/crypto_key").apply {
                        dataMap.putByteArray("aes_key", keyBytes)
                        dataMap.putLong("timestamp", System.currentTimeMillis())
                    }
                    Wearable.getDataClient(this@ReplyReceiverService)
                        .putDataItem(putReq.asPutDataRequest().setUrgent())
                        .await()
                    Log.d(TAG, "Encryption key synced to watch (direct)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-sync encryption key", e)
            }
        }
    }

    private fun sendActionResult(success: Boolean, message: String) {
        val json = JSONObject().apply {
            put("success", success)
            put("message", message)
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodeClient = Wearable.getNodeClient(this@ReplyReceiverService)
                val nodes = nodeClient.connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(this@ReplyReceiverService)
                        .sendMessage(node.id, "/action_result", json.toString().toByteArray())
                        .await()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send action result to watch", e)
            }
        }
    }
}
