package com.notifmirror.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class NotificationReceiverService : WearableListenerService() {

    companion object {
        private const val TAG = "NotifMirrorWear"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "WearableListenerService received message on path: ${messageEvent.path}")
        MessageHelper.handleMessage(this, messageEvent)
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
