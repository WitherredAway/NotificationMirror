package com.notifmirror.wear

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class NotificationReceiverService : WearableListenerService() {

    companion object {
        private const val TAG = "NotifMirrorWear"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "WearableListenerService received message on path: ${messageEvent.path}")
        when (messageEvent.path) {
            "/notification" -> {
                val decryptedData = decryptMessageData(messageEvent.data)
                if (decryptedData != null) {
                    NotificationHandler.handleNotification(this, DecryptedMessageEvent(messageEvent.path, decryptedData))
                } else {
                    // Decryption failed — queue for retry and request key re-sync
                    Log.w(TAG, "Cannot decrypt notification — queuing and requesting key re-sync")
                    PendingNotificationQueue.enqueue(messageEvent.data)
                    requestKeyFromPhone()
                }
            }
            "/notification_dismiss" -> {
                val decryptedDismiss = decryptMessageData(messageEvent.data)
                if (decryptedDismiss != null) {
                    NotificationHandler.handleDismissal(this, DecryptedMessageEvent(messageEvent.path, decryptedDismiss))
                } else {
                    // Fallback: try as plaintext for backward compatibility
                    NotificationHandler.handleDismissal(this, messageEvent)
                }
            }
            "/action_result" -> handleActionResult(messageEvent)
            "/set_app_sound" -> handleSetAppSound(messageEvent)
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val path = event.dataItem.uri.path ?: continue
                if (path == "/crypto_key") {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val keyBytes = dataMap.getByteArray("aes_key")
                    if (keyBytes != null) {
                        CryptoHelper.importKey(this, keyBytes)
                        Log.d(TAG, "Encryption key received and stored")
                        // Retry any queued notifications that failed decryption
                        PendingNotificationQueue.retryAll(this)
                    }
                } else if (path == "/mirroring_state") {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val enabled = dataMap.getBoolean("enabled", true)
                    val prefs = getSharedPreferences("notif_mirror_settings", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("mirroring_enabled", enabled).apply()
                    Log.d(TAG, "Mirroring state synced from phone: enabled=$enabled")
                }
            }
        }
    }

    private fun decryptMessageData(data: ByteArray): ByteArray? {
        return try {
            val key = CryptoHelper.getKey(this) ?: return null
            CryptoHelper.decrypt(data, key)
        } catch (e: Exception) {
            Log.w(TAG, "Decryption failed", e)
            null
        }
    }

    private fun requestKeyFromPhone() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodes = Wearable.getNodeClient(this@NotificationReceiverService).connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(this@NotificationReceiverService)
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
     * Handle per-app sound selection sent from the phone.
     * Stores the selected sound URI in watch SharedPreferences so
     * NotificationHandler can use it when creating notification channels.
     */
    private fun handleSetAppSound(messageEvent: MessageEvent) {
        try {
            val json = JSONObject(String(messageEvent.data))
            val packageName = json.getString("package")
            val soundUri = json.getString("soundUri")

            val prefs = getSharedPreferences("notif_sound_settings", Context.MODE_PRIVATE)
            if (soundUri == "default") {
                // Remove per-app override — use system default
                prefs.edit().remove("sound_$packageName").apply()
            } else {
                // "" = silent, or a specific URI string
                prefs.edit().putString("sound_$packageName", soundUri).apply()
            }
            Log.d(TAG, "Set sound for $packageName: $soundUri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle set_app_sound", e)
        }
    }

    private fun handleActionResult(messageEvent: MessageEvent) {
        try {
            val json = JSONObject(String(messageEvent.data))
            val success = json.getBoolean("success")
            val message = json.getString("message")

            Log.d(TAG, "Action result: success=$success message=$message")

            // Clear the timeout flag so the timeout toast doesn't fire
            ActionBroadcastReceiver.awaitingResult = false

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, message as CharSequence, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle action result", e)
        }
    }

    class DecryptedMessageEvent(
        private val msgPath: String,
        private val msgData: ByteArray
    ) : MessageEvent {
        override fun getPath(): String = msgPath
        override fun getData(): ByteArray = msgData
        override fun getRequestId(): Int = 0
        override fun getSourceNodeId(): String = ""
    }
}
