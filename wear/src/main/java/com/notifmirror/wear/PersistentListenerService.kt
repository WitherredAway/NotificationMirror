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
                        Log.d(TAG, "Encryption key received via PersistentListener")
                        PendingNotificationQueue.retryAll(this)
                    }
                } else if (path == "/mirroring_state") {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val enabled = dataMap.getBoolean("enabled", true)
                    val prefs = getSharedPreferences("notif_mirror_settings", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("mirroring_enabled", enabled).apply()
                    Log.d(TAG, "Mirroring state synced from phone via PersistentListener: enabled=$enabled")
                } else if (path == "/whitelisted_apps") {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val packages = dataMap.getStringArrayList("packages") ?: continue
                    val labels = dataMap.getStringArrayList("labels") ?: continue
                    preCreateNotificationChannels(packages, labels)
                }
            }
        }
    }

    private fun preCreateNotificationChannels(packages: List<String>, labels: List<String>) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        var created = 0
        for (i in packages.indices) {
            val pkg = packages[i]
            val label = if (i < labels.size) labels[i] else pkg.substringAfterLast('.')
            val channelId = "mirror_$pkg"
            val groupId = "group_$pkg"
            // Don't overwrite existing channels (preserves user customizations)
            if (nm.getNotificationChannel(channelId) != null) continue
            if (nm.notificationChannelGroups.none { it.id == groupId }) {
                nm.createNotificationChannelGroup(
                    android.app.NotificationChannelGroup(groupId, label)
                )
            }
            val channel = NotificationChannel(
                channelId, label, NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Mirrored notifications from $label"
                enableVibration(false)
                setSound(
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                group = groupId
            }
            nm.createNotificationChannel(channel)
            created++
        }
        Log.d(TAG, "Pre-created $created notification channels for ${packages.size} whitelisted apps")
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
