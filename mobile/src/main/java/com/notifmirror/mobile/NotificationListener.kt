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

        val pendingActions = java.util.concurrent.ConcurrentHashMap<String, Notification.Action>()
        // Track notification keys that were actually sent/queued to the watch
        // so we only send dismiss events for notifications the watch knows about
        private val sentNotificationKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())
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

    private fun syncEncryptionKey(retryCount: Int = 0) {
        scope.launch {
            syncEncryptionKeyBlocking(retryCount)
        }
    }

    private suspend fun syncEncryptionKeyBlocking(retryCount: Int = 0) {
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
            Log.w(TAG, "Failed to sync encryption key (attempt ${retryCount + 1})", e)
            if (retryCount < 3) {
                kotlinx.coroutines.delay(2000L * (retryCount + 1))
                syncEncryptionKeyBlocking(retryCount + 1)
            }
        }
    }

    /** Called by ReplyReceiverService when the watch requests the encryption key */
    suspend fun resyncEncryptionKey() {
        syncEncryptionKeyBlocking(0)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        // Master mirroring toggle
        if (!settings.isMirroringEnabled()) return
        // Background notification filter: None / Ongoing only / All persistent
        if (sbn.isOngoing) {
            val ongoingMode = settings.getEffectiveOngoingMode(sbn.packageName)
            when (ongoingMode) {
                SettingsManager.ONGOING_NONE -> return
                SettingsManager.ONGOING_ONLY -> {
                    val notification = sbn.notification ?: return
                    val isForegroundService = notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
                    if (isForegroundService) return
                }
                // ONGOING_ALL_PERSISTENT -> allow everything through
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

        // Extract stacked/conversation messages if available
        val conversationMessages = extractConversationMessages(notification, title, displayText)

        // Check app whitelist
        if (!settings.isAppWhitelisted(sbn.packageName)) {
            return
        }

        // Check global keyword filters
        if (!settings.passesKeywordFilter(title, displayText)) {
            Log.d(TAG, "Notification filtered out by global keyword rules: $title")
            return
        }

        // Check per-app keyword filters
        if (!settings.passesPerAppKeywordFilter(sbn.packageName, title, displayText)) {
            Log.d(TAG, "Notification filtered out by per-app keyword rules: $title (${sbn.packageName})")
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
            // Include stacked conversation messages so the watch can render them
            if (conversationMessages.isNotEmpty()) {
                val msgsArray = JSONArray()
                for ((sender, msgText) in conversationMessages) {
                    msgsArray.put(JSONObject().apply {
                        put("sender", sender)
                        put("text", msgText)
                    })
                }
                put("conversationMessages", msgsArray)
                put("isMessagingStyle", true)
            }
            // Send ongoing flag so watch can make persistent notifs persistent
            if (sbn.isOngoing) {
                put("isOngoing", true)
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
            // Send vibrate-only-when-unlocked setting (per-app with global fallback)
            put("vibrateOnlyWhenUnlocked", settings.getEffectiveVibrateOnlyWhenUnlocked(appPackageName))
            // Send effective vibration pattern (per-app or default)
            val effectiveVib = settings.getEffectiveVibrationPattern(appPackageName)
            if (effectiveVib.isNotEmpty()) {
                put("vibrationPattern", effectiveVib)
            }
            // Send complication settings so watch can filter by app
            put("complicationSource", settings.getComplicationSource())
            val complicationApp = settings.getComplicationApp()
            if (complicationApp.isNotEmpty()) {
                put("complicationApp", complicationApp)
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
                            "Watch disconnected, queued for delivery",
                            notifKey = notifKey,
                            actionsJson = actionsJson.toString()
                        )
                    }
                    return@launch
                }

                // Encrypt notification data before sending
                val plainBytes = json.toString().toByteArray(Charsets.UTF_8)
                val key = CryptoHelper.getOrCreateKey(this@NotificationListener)
                val messageBytes = CryptoHelper.encrypt(plainBytes, key)

                for (node in nodes) {
                    Wearable.getMessageClient(this@NotificationListener)
                        .sendMessage(node.id, PATH_NOTIFICATION, messageBytes)
                        .await()
                    Log.d(TAG, "Sent to node: ${node.displayName}")
                }
                sentNotificationKeys.add(notifKey)

                // Also flush any queued notifications
                if (!offlineQueue.isEmpty()) {
                    flushOfflineQueue(nodes)
                }

                if (settings.isKeepNotificationHistoryEnabled()) {
                    notifLog.addEntry(
                        appPackageName, title, displayText, "SENT",
                        "${actionsJson.length()} actions, sent to ${nodes.size} node(s)",
                        notifKey = notifKey,
                        actionsJson = actionsJson.toString()
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send notification to watch", e)
            }
        }
    }

    /**
     * Re-sends all currently active notifications to the watch, respecting all filters.
     * Returns the number of notifications synced.
     */
    fun syncAllActiveNotifications(onComplete: ((Int) -> Unit)? = null) {
        scope.launch {
            try {
                val activeNotifications = getActiveNotifications() ?: run {
                    onComplete?.invoke(0)
                    return@launch
                }

                val nodeClient = Wearable.getNodeClient(this@NotificationListener)
                val nodes = nodeClient.connectedNodes.await()
                if (nodes.isEmpty()) {
                    Log.w(TAG, "No connected watch nodes for sync")
                    onComplete?.invoke(0)
                    return@launch
                }

                val key = CryptoHelper.getOrCreateKey(this@NotificationListener)
                var syncCount = 0

                for (sbn in activeNotifications) {
                    if (sbn.packageName == packageName) continue
                    if (!settings.isMirroringEnabled()) continue

                    // Ongoing/persistent filter (use 3-option mode, same as onNotificationPosted)
                    if (sbn.isOngoing) {
                        val ongoingMode = settings.getEffectiveOngoingMode(sbn.packageName)
                        when (ongoingMode) {
                            SettingsManager.ONGOING_NONE -> continue
                            SettingsManager.ONGOING_ONLY -> {
                                val notification2 = sbn.notification ?: continue
                                val isForegroundService = notification2.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
                                if (isForegroundService) continue
                            }
                            // ONGOING_ALL_PERSISTENT -> allow everything through
                        }
                    }

                    // DND filter
                    if (settings.isDndSyncEnabled()) {
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        val interruptionFilter = nm.currentInterruptionFilter
                        if (interruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE ||
                            interruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALARMS) {
                            continue
                        }
                    }

                    // Screen-off mode filter
                    val screenMode = settings.getEffectiveScreenOffMode(sbn.packageName)
                    if (screenMode == SettingsManager.SCREEN_MODE_SCREEN_OFF_ONLY) {
                        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                        if (pm.isInteractive) continue
                    }

                    val notification = sbn.notification ?: continue
                    val extras = notification.extras ?: continue

                    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                    val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                    val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                    val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

                    if (title.isEmpty() && text.isEmpty()) continue

                    val displayText = bigText ?: text

                    // Extract stacked/conversation messages if available
                    val conversationMessages = extractConversationMessages(notification, title, displayText)

                    // App whitelist filter
                    if (!settings.isAppWhitelisted(sbn.packageName)) continue

                    // Global keyword filter
                    if (!settings.passesKeywordFilter(title, displayText)) continue

                    // Per-app keyword filter
                    if (!settings.passesPerAppKeywordFilter(sbn.packageName, title, displayText)) continue

                    val notifKey = sbn.key
                    val appPackageName = sbn.packageName
                    val postTime = sbn.postTime

                    val iconBase64 = getAppIconBase64(appPackageName)

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
                        // Include stacked conversation messages so the watch can render them
                        if (conversationMessages.isNotEmpty()) {
                            val msgsArray = JSONArray()
                            for ((sender, msgText) in conversationMessages) {
                                msgsArray.put(JSONObject().apply {
                                    put("sender", sender)
                                    put("text", msgText)
                                })
                            }
                            put("conversationMessages", msgsArray)
                            put("isMessagingStyle", true)
                        }
                        if (iconBase64 != null) put("icon", iconBase64)
                        // Send ongoing flag so watch can make persistent notifs persistent
                        if (sbn.isOngoing) {
                            put("isOngoing", true)
                        }
                        put("muteDuration", settings.getEffectiveMuteDuration(appPackageName))
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
                        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                        if (screenMode == SettingsManager.SCREEN_MODE_SILENT_WHEN_ON && pm.isInteractive) {
                            put("silent", true)
                        }
                        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                        if (settings.isHideWhenLockedEnabled() && km.isKeyguardLocked) {
                            put("hideContent", true)
                        }
                        put("muteContinuation", settings.getEffectiveMuteContinuation(appPackageName))
                        put("batterySaverEnabled", settings.isBatterySaverEnabled())
                        put("batterySaverThreshold", settings.getBatterySaverThreshold())
                        val effectiveVib = settings.getEffectiveVibrationPattern(appPackageName)
                        if (effectiveVib.isNotEmpty()) put("vibrationPattern", effectiveVib)
                        put("complicationSource", settings.getComplicationSource())
                        val complicationApp = settings.getComplicationApp()
                        if (complicationApp.isNotEmpty()) put("complicationApp", complicationApp)
                    }

                    val plainBytes = json.toString().toByteArray(Charsets.UTF_8)
                    val messageBytes = CryptoHelper.encrypt(plainBytes, key)

                    for (node in nodes) {
                        Wearable.getMessageClient(this@NotificationListener)
                            .sendMessage(node.id, PATH_NOTIFICATION, messageBytes)
                            .await()
                    }
                    sentNotificationKeys.add(notifKey)
                    syncCount++
                    Log.d(TAG, "Synced notification: $title from $appPackageName")
                }

                Log.d(TAG, "Sync complete: $syncCount notifications sent")
                onComplete?.invoke(syncCount)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync notifications", e)
                onComplete?.invoke(0)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val keysToRemove = pendingActions.keys.filter { it.startsWith(sbn.key + ":") }
        keysToRemove.forEach { pendingActions.remove(it) }

        // Only send dismiss events for notifications we actually sent to the watch
        if (!sentNotificationKeys.remove(sbn.key)) return
        if (!settings.getEffectiveAutoDismissSync(sbn.packageName)) return

        val json = JSONObject().apply {
            put("action", "dismiss")
            put("key", sbn.key)
            put("package", sbn.packageName)
        }

        Log.d(TAG, "Sending dismiss event for ${sbn.packageName}")

        scope.launch {
            try {
                val key = CryptoHelper.getOrCreateKey(this@NotificationListener)
                val plainBytes = json.toString().toByteArray(Charsets.UTF_8)
                val encryptedBytes = CryptoHelper.encrypt(plainBytes, key)
                sendToWatch(encryptedBytes, PATH_DISMISS)
                Log.d(TAG, "Dismiss synced to watch for ${sbn.packageName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send dismissal to watch", e)
            }
        }
    }

    /**
     * Extract conversation/stacked messages from a notification.
     * Checks MessagingStyle EXTRA_MESSAGES first, then InboxStyle EXTRA_TEXT_LINES.
     * Returns a list of (sender, text) pairs, or empty if no stacked messages found.
     */
    private fun extractConversationMessages(notification: Notification, title: String, text: String): List<Pair<String, String>> {
        val extras = notification.extras ?: return emptyList()
        val messages = mutableListOf<Pair<String, String>>()

        // Try MessagingStyle messages first (EXTRA_MESSAGES)
        val msgBundle = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (msgBundle != null && msgBundle.isNotEmpty()) {
            for (item in msgBundle) {
                if (item is android.os.Bundle) {
                    // Extract sender name: try deprecated CharSequence "sender" first,
                    // then extract from "sender_person" Person parcelable (newer API),
                    // fall back to "You" for self-messages (null sender = current user convention)
                    val sender = item.getCharSequence("sender")?.toString()
                        ?: run {
                            // sender_person is a Person Parcelable, not a CharSequence
                            val personBundle = item.getBundle("sender_person")
                            personBundle?.getString("name")
                                ?: (item.getParcelable<android.app.Person>("sender_person"))?.name?.toString()
                        }
                        ?: "You"
                    val msgText = item.getCharSequence("text")?.toString() ?: continue
                    messages.add(Pair(sender, msgText))
                }
            }
            if (messages.isNotEmpty()) return messages
        }

        // Try InboxStyle text lines (EXTRA_TEXT_LINES)
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (textLines != null && textLines.isNotEmpty()) {
            for (line in textLines) {
                val lineStr = line?.toString() ?: continue
                // InboxStyle lines often have "Sender: message" format
                val colonIdx = lineStr.indexOf(": ")
                if (colonIdx > 0 && colonIdx < 30) {
                    messages.add(Pair(lineStr.substring(0, colonIdx), lineStr.substring(colonIdx + 2)))
                } else {
                    messages.add(Pair(title, lineStr))
                }
            }
            if (messages.isNotEmpty()) return messages
        }

        return emptyList()
    }

    private fun getAppIconBase64(packageName: String): String? {
        return WearSyncHelper.getAppIconBase64(this, packageName)
    }

    private suspend fun flushOfflineQueue(nodes: Collection<com.google.android.gms.wearable.Node>) {
        try {
            val queued = offlineQueue.dequeueAll()
            Log.d(TAG, "Flushing ${queued.size} queued notifications")
            val key = CryptoHelper.getOrCreateKey(this@NotificationListener)
            for (queuedJson in queued) {
                try {
                    val plainBytes = queuedJson.toString().toByteArray(Charsets.UTF_8)
                    val messageBytes = CryptoHelper.encrypt(plainBytes, key)
                    for (node in nodes) {
                        Wearable.getMessageClient(this@NotificationListener)
                            .sendMessage(node.id, PATH_NOTIFICATION, messageBytes)
                            .await()
                    }
                    val queuedKey = queuedJson.optString("key", "")
                    if (queuedKey.isNotEmpty()) sentNotificationKeys.add(queuedKey)
                    Log.d(TAG, "Delivered queued notification: ${queuedJson.optString("package", "unknown")}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send queued notification, re-queuing", e)
                    offlineQueue.enqueue(queuedJson)
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
