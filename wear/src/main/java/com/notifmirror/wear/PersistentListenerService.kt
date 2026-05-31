package com.notifmirror.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class PersistentListenerService : Service(),
    MessageClient.OnMessageReceivedListener,
    DataClient.OnDataChangedListener,
    CapabilityClient.OnCapabilityChangedListener {

    companion object {
        private const val TAG = "NotifMirrorWear"
        private const val CHANNEL_ID = "persistent_listener"
        private const val NOTIFICATION_ID = 1
        private const val PHONE_CAPABILITY = "notif_mirror_phone"
        private const val RECONCILE_MIN_INTERVAL_MS = 30_000L

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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var phoneConnected = false
    @Volatile private var lastReconcileRequestMs = 0L

    // Fires when the watch screen turns on (e.g. the user picks it up after it was
    // charging/asleep). Triggers a lightweight reconcile so notifications dismissed on
    // the phone while the watch was idle are cleared. Debounced to avoid spamming on
    // every wrist raise. Screen on/off cannot be declared in the manifest, so this is
    // registered at runtime while the foreground service is alive.
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                requestReconcileDebounced()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PersistentListenerService created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        messageClient = Wearable.getMessageClient(this)
        messageClient.addListener(this)
        Wearable.getDataClient(this).addListener(this)
        // Listen for phone capability changes to detect reconnection
        Wearable.getCapabilityClient(this).addListener(this, PHONE_CAPABILITY)
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        Log.d(TAG, "MessageClient, DataClient, CapabilityClient and screen-on listeners registered")

        // Check initial phone connection state
        scope.launch {
            try {
                val capabilityInfo = Wearable.getCapabilityClient(this@PersistentListenerService)
                    .getCapability(PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                    .await()
                phoneConnected = capabilityInfo.nodes.isNotEmpty()
                Log.d(TAG, "Initial phone capability nodes: ${capabilityInfo.nodes.size}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check initial phone capability", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        messageClient.removeListener(this)
        Wearable.getDataClient(this).removeListener(this)
        Wearable.getCapabilityClient(this).removeListener(this)
        try {
            unregisterReceiver(screenOnReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "screenOnReceiver was not registered", e)
        }
        Log.d(TAG, "PersistentListenerService destroyed, listeners removed")
    }

    /**
     * Ask the phone for a reconcile so stale notifications get cleared, but at most
     * once per [RECONCILE_MIN_INTERVAL_MS] so frequent wrist-raises don't spam the link.
     */
    private fun requestReconcileDebounced() {
        val now = System.currentTimeMillis()
        if (now - lastReconcileRequestMs < RECONCILE_MIN_INTERVAL_MS) return
        lastReconcileRequestMs = now
        scope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@PersistentListenerService)
                    .connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(this@PersistentListenerService)
                        .sendMessage(node.id, "/request_reconcile", ByteArray(0))
                        .await()
                }
                Log.d(TAG, "Requested reconcile on screen-on (${nodes.size} nodes)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request reconcile on screen-on", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "PersistentListener received message on path: ${messageEvent.path}")
        MessageHelper.handleMessage(this, messageEvent)
    }

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        val hasPhone = capabilityInfo.nodes.isNotEmpty()
        Log.d(TAG, "Phone capability changed: ${capabilityInfo.nodes.size} nodes (was connected=$phoneConnected)")

        if (hasPhone && !phoneConnected) {
            // Phone just reconnected — request a full sync so reconciliation
            // cleans up any stale notifications from missed dismissals
            // (e.g. watch was locked/off wrist when phone dismissed notifications)
            Log.d(TAG, "Phone reconnected — requesting sync for reconciliation")
            scope.launch {
                try {
                    // Small delay to let the connection stabilize
                    delay(1500)
                    for (node in capabilityInfo.nodes) {
                        Wearable.getMessageClient(this@PersistentListenerService)
                            .sendMessage(node.id, "/request_sync", ByteArray(0))
                            .await()
                    }
                    Log.d(TAG, "Reconnection sync requested from phone")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to request reconnection sync", e)
                }
            }
        }
        phoneConnected = hasPhone
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
