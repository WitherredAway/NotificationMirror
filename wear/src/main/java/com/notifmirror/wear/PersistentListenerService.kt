package com.notifmirror.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class PersistentListenerService : Service(), MessageClient.OnMessageReceivedListener, DataClient.OnDataChangedListener {

    companion object {
        private const val TAG = "NotifMirrorWear"
        private const val CHANNEL_ID = "persistent_listener"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, PersistentListenerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PersistentListenerService::class.java))
        }
    }

    private lateinit var messageClient: MessageClient

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PersistentListenerService created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        messageClient = Wearable.getMessageClient(this)
        messageClient.addListener(this)
        Wearable.getDataClient(this).addListener(this)
        Log.d(TAG, "MessageClient and DataClient listeners registered")
    }

    override fun onDestroy() {
        super.onDestroy()
        messageClient.removeListener(this)
        Wearable.getDataClient(this).removeListener(this)
        Log.d(TAG, "PersistentListenerService destroyed, listeners removed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "PersistentListener received message on path: ${messageEvent.path}")
        when (messageEvent.path) {
            "/notification" -> {
                val decryptedData = decryptMessageData(messageEvent.data)
                if (decryptedData != null) {
                    NotificationHandler.handleNotification(this, NotificationReceiverService.DecryptedMessageEvent(messageEvent.path, decryptedData))
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
                    NotificationHandler.handleDismissal(this, NotificationReceiverService.DecryptedMessageEvent(messageEvent.path, decryptedDismiss))
                } else {
                    // Fallback: try as plaintext for backward compatibility
                    NotificationHandler.handleDismissal(this, messageEvent)
                }
            }
            "/action_result" -> handleActionResult(messageEvent)
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
                val nodes = Wearable.getNodeClient(this@PersistentListenerService).connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(this@PersistentListenerService)
                        .sendMessage(node.id, "/request_key", byteArrayOf())
                        .await()
                }
                Log.d(TAG, "Sent /request_key to phone")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request key from phone", e)
            }
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
                        Log.d(TAG, "Encryption key received via PersistentListener")
                        PendingNotificationQueue.retryAll(this)
                    }
                } else if (path == "/mirroring_state") {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val enabled = dataMap.getBoolean("enabled", true)
                    val prefs = getSharedPreferences("notif_mirror_settings", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("mirroring_enabled", enabled).apply()
                    Log.d(TAG, "Mirroring state synced from phone via PersistentListener: enabled=$enabled")
                }
            }
        }
    }

    private fun handleActionResult(messageEvent: MessageEvent) {
        try {
            val json = org.json.JSONObject(String(messageEvent.data))
            val success = json.getBoolean("success")
            val message = json.getString("message")

            Log.d(TAG, "Action result: success=$success message=$message")
            ActionBroadcastReceiver.awaitingResult = false

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(this, message as CharSequence, android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle action result", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Notification Mirror Listener",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Keeps notification mirroring active"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Notification Mirror")
            .setContentText("Listening for notifications")
            .setOngoing(true)
            .build()
    }
}
