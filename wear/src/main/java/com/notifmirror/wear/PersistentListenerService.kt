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
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable

class PersistentListenerService : Service(), MessageClient.OnMessageReceivedListener {

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
        Log.d(TAG, "MessageClient listener registered")
    }

    override fun onDestroy() {
        super.onDestroy()
        messageClient.removeListener(this)
        Log.d(TAG, "PersistentListenerService destroyed, listener removed")
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
                    NotificationHandler.handleNotification(this, messageEvent)
                }
            }
            "/notification_dismiss" -> NotificationHandler.handleDismissal(this, messageEvent)
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
