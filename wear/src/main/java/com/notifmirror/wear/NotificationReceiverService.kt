package com.notifmirror.wear

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
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
                    NotificationHandler.handleNotification(this, messageEvent)
                }
            }
            "/notification_dismiss" -> NotificationHandler.handleDismissal(this, messageEvent)
            "/action_result" -> handleActionResult(messageEvent)
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
                    }
                }
            }
        }
    }

    private fun decryptMessageData(data: ByteArray): ByteArray? {
        return try {
            val key = CryptoHelper.getKey(this) ?: return null
            CryptoHelper.decrypt(data, key)
        } catch (e: Exception) {
            Log.w(TAG, "Decryption failed, trying as plaintext", e)
            null
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
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
