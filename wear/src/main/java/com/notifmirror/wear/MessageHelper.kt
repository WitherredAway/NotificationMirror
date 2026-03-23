package com.notifmirror.wear

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Shared message handling logic used by both NotificationReceiverService
 * (WearableListenerService) and PersistentListenerService (foreground service).
 * Avoids duplicating decryption, routing, and action result handling code.
 */
object MessageHelper {

    private const val TAG = "NotifMirrorWear"

    // Dedup cache: both NotificationReceiverService (WearableListenerService) and
    // PersistentListenerService (foreground service) receive the same message.
    // Track recent message hashes to skip duplicate processing.
    private val recentMessageHashes = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private const val DEDUP_WINDOW_MS = 2000L

    /**
     * Route an incoming message to the appropriate handler based on its path.
     */
    fun handleMessage(context: Context, messageEvent: MessageEvent) {
        Log.d(TAG, "MessageHelper received message on path: ${messageEvent.path}")

        // Deduplicate: both WearableListenerService and PersistentListenerService
        // receive the same message. Skip if we already processed this exact message.
        val msgHash = (messageEvent.path.hashCode() * 31 + messageEvent.data.contentHashCode())
        val now = System.currentTimeMillis()
        val previous = recentMessageHashes.put(msgHash, now)
        if (previous != null && now - previous < DEDUP_WINDOW_MS) {
            Log.d(TAG, "Skipping duplicate message on path: ${messageEvent.path}")
            return
        }
        // Prune old entries
        recentMessageHashes.entries.removeAll { now - it.value > DEDUP_WINDOW_MS * 2 }

        when (messageEvent.path) {
            "/notification" -> {
                val decryptedData = decryptMessageData(context, messageEvent.data)
                if (decryptedData != null) {
                    NotificationHandler.handleNotification(
                        context,
                        NotificationReceiverService.DecryptedMessageEvent(messageEvent.path, decryptedData)
                    )
                } else {
                    Log.w(TAG, "Cannot decrypt notification — queuing and requesting key re-sync")
                    PendingNotificationQueue.enqueue(messageEvent.data)
                    requestKeyFromPhone(context)
                }
            }
            "/notification_dismiss" -> {
                val decryptedDismiss = decryptMessageData(context, messageEvent.data)
                if (decryptedDismiss != null) {
                    NotificationHandler.handleDismissal(
                        context,
                        NotificationReceiverService.DecryptedMessageEvent(messageEvent.path, decryptedDismiss)
                    )
                } else {
                    Log.w(TAG, "Cannot decrypt dismiss — dropping (key not available)")
                }
            }
            "/notification_reconcile" -> {
                val decryptedReconcile = decryptMessageData(context, messageEvent.data)
                if (decryptedReconcile != null) {
                    NotificationHandler.handleReconciliation(
                        context,
                        NotificationReceiverService.DecryptedMessageEvent(messageEvent.path, decryptedReconcile)
                    )
                } else {
                    Log.w(TAG, "Cannot decrypt reconcile — dropping (key not available)")
                }
            }
            "/action_result" -> handleActionResult(context, messageEvent)
            "/request_logcat" -> handleLogcatRequest(context, messageEvent)
        }
    }

    /**
     * Decrypt message data using the stored encryption key.
     */
    fun decryptMessageData(context: Context, data: ByteArray): ByteArray? {
        return try {
            val key = CryptoHelper.getKey(context) ?: return null
            CryptoHelper.decrypt(data, key)
        } catch (e: Exception) {
            Log.w(TAG, "Decryption failed", e)
            null
        }
    }

    /**
     * Send a /request_key message to the phone to re-sync the encryption key.
     */
    fun requestKeyFromPhone(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, "/request_key", byteArrayOf())
                        .await()
                }
                Log.d(TAG, "Sent /request_key to phone")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request key from phone", e)
            }
        }
    }

    /**
     * Handle a logcat request from the phone — capture watch logcat and send it back.
     */
    fun handleLogcatRequest(context: Context, messageEvent: MessageEvent) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "2000"))
                val logcatOutput = process.inputStream.bufferedReader().readText()
                val responseBytes = logcatOutput.toByteArray(Charsets.UTF_8)

                val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                for (node in nodes) {
                    // WearOS MessageClient has ~100KB limit; truncate if needed
                    val payload = if (responseBytes.size > 80_000) {
                        val truncated = logcatOutput.takeLast(78_000)
                        "(Truncated — showing last 78KB of logcat)\n$truncated".toByteArray(Charsets.UTF_8)
                    } else {
                        responseBytes
                    }
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, "/logcat_response", payload)
                        .await()
                }
                Log.d(TAG, "Sent logcat response (${responseBytes.size} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send logcat response", e)
            }
        }
    }

    /**
     * Handle an action result message from the phone (reply sent, action executed, etc.).
     */
    fun handleActionResult(context: Context, messageEvent: MessageEvent) {
        try {
            val json = JSONObject(String(messageEvent.data))
            val success = json.getBoolean("success")
            val message = json.getString("message")

            Log.d(TAG, "Action result: success=$success message=$message")
            ActionBroadcastReceiver.awaitingResult = false

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, message as CharSequence, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle action result", e)
        }
    }
}
