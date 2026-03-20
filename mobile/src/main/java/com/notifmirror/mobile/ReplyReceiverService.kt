package com.notifmirror.mobile

import android.app.Notification
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
            "/open_url" -> handleOpenUrl(messageEvent)
            "/snooze" -> handleSnooze(messageEvent)
            "/request_key" -> handleKeyRequest()
            "/mirroring_toggle" -> handleMirroringToggle(messageEvent)
            "/request_sync" -> handleRequestSync()
            "/resend_ongoing" -> handleResendOngoing(messageEvent)
        }
    }

    private fun handleOpenSettings() {
        Log.d(TAG, "Received request to open settings from watch")
        val intent = Intent(this, AppSettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun handleOpenUrl(messageEvent: MessageEvent) {
        try {
            val url = String(messageEvent.data, Charsets.UTF_8)
            Log.d(TAG, "Received request to open URL from watch: $url")
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open URL from watch", e)
        }
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
                ?: recoverAction(notifKey, actionIndex)
            if (action == null) {
                Log.w(TAG, "No pending action found for key: $actionKey")
                sendActionResult(false, "Notification expired — reply not sent")
                return
            }

            // Try sending the reply, with fresh-action retry on CanceledException
            if (!trySendReply(action, replyText, actionKey)) {
                // Stored PendingIntent was stale — try recovering a fresh one
                Log.d(TAG, "PendingIntent stale for $actionKey, attempting fresh recovery")
                NotificationListener.pendingActions.remove(actionKey)
                val freshAction = recoverAction(notifKey, actionIndex)
                if (freshAction != null && trySendReply(freshAction, replyText, actionKey)) {
                    return // success on retry
                }
                sendActionResult(false, "Notification expired — reply not sent")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle reply", e)
            sendActionResult(false, "Reply failed: ${e.message}")
        }
    }

    /**
     * Attempt to send a reply using the given action's PendingIntent + RemoteInput.
     * Returns true on success. Returns false if the PendingIntent is cancelled/stale.
     * Throws on unexpected errors.
     */
    private fun trySendReply(action: Notification.Action, replyText: String, actionKey: String): Boolean {
        val remoteInputs = action.remoteInputs
        if (remoteInputs == null || remoteInputs.isEmpty()) {
            Log.w(TAG, "No remote inputs found for action $actionKey")
            sendActionResult(false, "Reply input not available")
            return true // not a retryable error, treat as handled
        }

        return try {
            val intent = Intent()
            val bundle = Bundle()
            for (ri in remoteInputs) {
                bundle.putCharSequence(ri.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(action.remoteInputs, intent, bundle)
            action.actionIntent.send(this, 0, intent)

            Log.d(TAG, "Reply sent successfully for $actionKey")
            sendActionResult(true, "Reply sent")
            true
        } catch (e: android.app.PendingIntent.CanceledException) {
            Log.w(TAG, "PendingIntent cancelled for $actionKey", e)
            false
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
                ?: recoverAction(notifKey, actionIndex)
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

    private fun handleMirroringToggle(messageEvent: MessageEvent) {
        try {
            val json = JSONObject(String(messageEvent.data))
            val enabled = json.getBoolean("enabled")
            Log.d(TAG, "Received mirroring toggle from watch: enabled=$enabled")
            val settings = SettingsManager(this)
            settings.setMirroringEnabled(enabled)
            // Sync the new state back to watch via DataClient so the watch UI updates
            // (DataClient requires data to actually change, so include a timestamp)
            syncMirroringToWatch(enabled)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle mirroring toggle", e)
        }
    }

    private fun syncMirroringToWatch(enabled: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            WearSyncHelper.syncMirroringToWatch(this@ReplyReceiverService, enabled)
        }
    }

    /**
     * Handle sync request from watch — re-sends all active notifications.
     */
    private fun handleRequestSync() {
        Log.d(TAG, "Received sync request from watch")
        val listener = NotificationListener.instance
        if (listener != null) {
            listener.syncAllActiveNotifications { count ->
                Log.d(TAG, "Synced $count notifications to watch (requested by watch)")
            }
        } else {
            Log.w(TAG, "NotificationListener not active — cannot sync")
        }
    }

    /**
     * Handle resend request for an ongoing notification that was dismissed from the watch.
     * Checks if the notification is still active on the phone and re-triggers forwarding.
     */
    private fun handleResendOngoing(messageEvent: MessageEvent) {
        try {
            val json = JSONObject(String(messageEvent.data))
            val notifKey = json.getString("key")

            Log.d(TAG, "Watch dismissed ongoing notification, checking if still active: $notifKey")

            val listener = NotificationListener.instance
            if (listener == null) {
                Log.w(TAG, "NotificationListener not active — cannot resend ongoing")
                return
            }

            // Find the notification in active notifications
            val activeNotifs = listener.getActiveNotifications() ?: return
            val sbn = activeNotifs.find { it.key == notifKey }
            if (sbn == null) {
                Log.d(TAG, "Notification $notifKey no longer active — not resending")
                return
            }

            if (!sbn.isOngoing) {
                Log.d(TAG, "Notification $notifKey is no longer ongoing — not resending")
                return
            }

            // Clear content hash so the resend isn't blocked by dedup
            // (content hasn't changed, but we need to re-send it)
            NotificationListener.lastContentHash.remove(notifKey)

            // Re-trigger onNotificationPosted to resend it to the watch
            Log.d(TAG, "Resending ongoing notification to watch: $notifKey")
            listener.onNotificationPosted(sbn)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle resend ongoing", e)
        }
    }

    /**
     * Recover a notification action from active notifications when pendingActions
     * doesn't have it (e.g. after NotificationListener service restart which
     * clears the in-memory map). Re-populates pendingActions for future use.
     */
    private fun recoverAction(notifKey: String, actionIndex: Int): Notification.Action? {
        val listener = NotificationListener.instance ?: return null
        val activeNotifs = listener.getActiveNotifications() ?: return null
        val sbn = activeNotifs.find { it.key == notifKey } ?: return null
        val actions = sbn.notification?.actions ?: return null

        Log.d(TAG, "Recovering actions from active notification: $notifKey (${actions.size} actions)")

        // Re-populate pendingActions for ALL actions on this notification
        for ((index, action) in actions.withIndex()) {
            NotificationListener.pendingActions["$notifKey:$index"] = action
        }

        return if (actionIndex < actions.size) actions[actionIndex] else null
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
