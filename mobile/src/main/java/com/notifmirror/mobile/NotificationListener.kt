package com.notifmirror.mobile

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.PowerManager
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class NotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifMirror"
        private const val PATH_NOTIFICATION = "/notification"
        private const val PATH_DISMISS = "/notification_dismiss"

        val pendingActions = mutableMapOf<String, Notification.Action>()
        var instance: NotificationListener? = null
            private set
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var settings: SettingsManager
    private lateinit var notifLog: NotificationLog
    private lateinit var offlineQueue: OfflineQueue

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsManager(this)
        notifLog = NotificationLog(this)
        offlineQueue = OfflineQueue(this)
        syncEncryptionKey()
    }

    private fun syncEncryptionKey() {
        scope.launch {
            try {
                val keyBytes = CryptoHelper.getKeyBytes(this@NotificationListener)
                val putReq = PutDataMapRequest.create("/crypto_key").apply {
                    dataMap.putByteArray("aes_key", keyBytes)
                    dataMap.putLong("timestamp", System.currentTimeMillis())
                }
                Wearable.getDataClient(this@NotificationListener)
                    .putDataItem(putReq.asPutDataRequest().setUrgent())
                    .await()
                Log.d(TAG, "Encryption key synced to watch")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync encryption key", e)
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        // Separate ongoing (music, timers) from persistent (foreground services)
        if (sbn.isOngoing) {
            val notification = sbn.notification ?: return
            val isForegroundService = notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
            if (isForegroundService) {
                if (!settings.getEffectiveMirrorPersistent(sbn.packageName)) return
            } else {
                if (!settings.getEffectiveMirrorOngoing(sbn.packageName)) return
            }
        }

        // Check DND mode (only if DND sync is enabled)
        if (settings.isDndSyncEnabled()) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val interruptionFilter = nm.currentInterruptionFilter
            if (interruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE ||
                interruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALARMS) {
                return
            }
        }

        // Check screen-off mode (per-app with global fallback)
        val screenMode = settings.getEffectiveScreenOffMode(sbn.packageName)
        if (screenMode == SettingsManager.SCREEN_MODE_SCREEN_OFF_ONLY) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isInteractive) {
                return
            }
        }

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        if (title.isEmpty() && text.isEmpty()) return

        val displayText = bigText ?: text

        // Check app whitelist
        if (!settings.isAppWhitelisted(sbn.packageName)) {
            return
        }

        // Check keyword filters
        if (!settings.passesKeywordFilter(title, displayText)) {
            Log.d(TAG, "Notification filtered out by keyword rules: $title")
            return
        }

        val notifKey = sbn.key
        val appPackageName = sbn.packageName
        val postTime = sbn.postTime

        // Get app icon as base64
        val iconBase64 = getAppIconBase64(appPackageName)

        // Process ALL actions and store them
        val actionsJson = JSONArray()
        val actions = notification.actions
        if (actions != null) {
            for ((index, action) in actions.withIndex()) {
                val actionKey = "$notifKey:$index"
                pendingActions[actionKey] = action

                val hasRemoteInput = action.remoteInputs != null && action.remoteInputs.isNotEmpty()
                val actionJson = JSONObject().apply {
                    put("index", index)
                    put("title", action.title?.toString() ?: "Action $index")
                    put("hasRemoteInput", hasRemoteInput)
                }
                actionsJson.put(actionJson)
            }
        }

        // Resolve actual app label from PackageManager
        val appLabel = try {
            val ai = packageManager.getApplicationInfo(appPackageName, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            appPackageName.split(".").lastOrNull()?.replaceFirstChar { it.uppercase() } ?: appPackageName
        }

        val json = JSONObject().apply {
            put("key", notifKey)
            put("package", appPackageName)
            put("appLabel", appLabel)
            put("title", title)
            put("text", displayText)
            put("subText", subText ?: "")
            put("postTime", postTime)
            put("actions", actionsJson)
            if (iconBase64 != null) {
                put("icon", iconBase64)
            }
            put("muteDuration", settings.getEffectiveMuteDuration(appPackageName))
            // Send all configurable values to watch (per-app with fallback to global)
            put("notifPriority", settings.getEffectivePriority(appPackageName))
            put("bigTextThreshold", settings.getEffectiveBigTextThreshold(appPackageName))
            put("autoCancel", settings.getEffectiveAutoCancel(appPackageName))
            put("autoDismissSync", settings.getEffectiveAutoDismissSync(appPackageName))
            put("showOpenButton", settings.getEffectiveShowOpenButton(appPackageName))
            put("showMuteButton", settings.getEffectiveShowMuteButton(appPackageName))
            put("showSnoozeButton", settings.getEffectiveShowSnoozeButton(appPackageName))
            put("snoozeDuration", settings.getEffectiveSnoozeDuration(appPackageName))
            put("defaultVibration", settings.getDefaultVibrationPattern())
            put("keepHistory", settings.isKeepNotificationHistoryEnabled())
            // Send screen mode so watch knows whether to be silent
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (screenMode == SettingsManager.SCREEN_MODE_SILENT_WHEN_ON && pm.isInteractive) {
                put("silent", true)
            }
            // Hide notification content if phone is locked and setting is enabled
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (settings.isHideWhenLockedEnabled() && km.isKeyguardLocked) {
                put("hideContent", true)
            }
            // Send mute continuation setting (per-app with global fallback)
            put("muteContinuation", settings.getEffectiveMuteContinuation(appPackageName))
            // Send battery saver settings so watch can check locally
            put("batterySaverEnabled", settings.isBatterySaverEnabled())
            put("batterySaverThreshold", settings.getBatterySaverThreshold())
            // Send effective vibration pattern (per-app or default)
            val effectiveVib = settings.getEffectiveVibrationPattern(appPackageName)
            if (effectiveVib.isNotEmpty()) {
                put("vibrationPattern", effectiveVib)
            }
            // Send custom sound URI if set for this app
            val customSound = settings.getEffectiveSoundUri(appPackageName)
            if (customSound.isNotEmpty()) {
                put("soundUri", customSound)
            }
        }

        Log.d(TAG, "Forwarding notification: $title from $appPackageName (${actionsJson.length()} actions)")

        scope.launch {
            try {
                val nodeClient = Wearable.getNodeClient(this@NotificationListener)
                val nodes = nodeClient.connectedNodes.await()

                if (nodes.isEmpty()) {
                    Log.w(TAG, "No connected watch nodes found")
                    // Queue for offline delivery
                    offlineQueue.enqueue(json)
                    if (settings.isKeepNotificationHistoryEnabled()) {
                        notifLog.addEntry(
                            appPackageName, title, displayText, "QUEUED",
                            "Watch disconnected, queued for delivery"
                        )
                    }
                    return@launch
                }

                // Encrypt notification data before sending
                val plainBytes = json.toString().toByteArray(Charsets.UTF_8)
                val key = CryptoHelper.getOrCreateKey(this@NotificationListener)
                val encryptedBytes = CryptoHelper.encrypt(plainBytes, key)

                for (node in nodes) {
                    Wearable.getMessageClient(this@NotificationListener)
                        .sendMessage(node.id, PATH_NOTIFICATION, encryptedBytes)
                        .await()
                    Log.d(TAG, "Sent encrypted to node: ${node.displayName}")
                }

                // Also flush any queued notifications
                if (!offlineQueue.isEmpty()) {
                    flushOfflineQueue(nodes)
                }

                if (settings.isKeepNotificationHistoryEnabled()) {
                    notifLog.addEntry(
                        appPackageName, title, displayText, "SENT",
                        "${actionsJson.length()} actions, sent to ${nodes.size} node(s)"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send notification to watch", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val keysToRemove = pendingActions.keys.filter { it.startsWith(sbn.key + ":") }
        keysToRemove.forEach { pendingActions.remove(it) }

        if (!settings.getEffectiveAutoDismissSync(sbn.packageName)) return

        val json = JSONObject().apply {
            put("action", "dismiss")
            put("key", sbn.key)
        }

        scope.launch {
            try {
                sendToWatch(json.toString().toByteArray(), PATH_DISMISS)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send dismissal to watch", e)
            }
        }
    }

    private fun getAppIconBase64(packageName: String): String? {
        return try {
            val iconSize = 48
            val iconQuality = 80
            val drawable = packageManager.getApplicationIcon(packageName)
            val bitmap = if (drawable is BitmapDrawable) {
                Bitmap.createScaledBitmap(drawable.bitmap, iconSize, iconSize, true)
            } else {
                val bmp = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, iconSize, iconSize)
                drawable.draw(canvas)
                bmp
            }
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, iconQuality, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get icon for $packageName", e)
            null
        }
    }

    private suspend fun flushOfflineQueue(nodes: Collection<com.google.android.gms.wearable.Node>) {
        try {
            val queued = offlineQueue.dequeueAll()
            Log.d(TAG, "Flushing ${queued.size} queued notifications")
            val key = CryptoHelper.getOrCreateKey(this@NotificationListener)
            for (queuedJson in queued) {
                try {
                    val plainBytes = queuedJson.toString().toByteArray(Charsets.UTF_8)
                    val encryptedBytes = CryptoHelper.encrypt(plainBytes, key)
                    for (node in nodes) {
                        Wearable.getMessageClient(this@NotificationListener)
                            .sendMessage(node.id, PATH_NOTIFICATION, encryptedBytes)
                            .await()
                    }
                    val pkg = queuedJson.optString("package", "unknown")
                    val title = queuedJson.optString("title", "")
                    if (settings.isKeepNotificationHistoryEnabled()) {
                        notifLog.addEntry(pkg, title, "", "DELIVERED", "From offline queue")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send queued notification", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to flush offline queue", e)
        }
    }

    private suspend fun sendToWatch(data: ByteArray, path: String = PATH_NOTIFICATION) {
        val nodeClient = Wearable.getNodeClient(this)
        val nodes = nodeClient.connectedNodes.await()
        for (node in nodes) {
            Wearable.getMessageClient(this)
                .sendMessage(node.id, path, data)
                .await()
            Log.d(TAG, "Sent to node: ${node.displayName}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scope.cancel()
    }
}
