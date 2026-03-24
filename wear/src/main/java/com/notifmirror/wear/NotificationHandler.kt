package com.notifmirror.wear

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.google.android.gms.wearable.MessageEvent
import org.json.JSONObject

object NotificationHandler {

    private const val TAG = "NotifMirrorWear"
    private const val CHANNEL_PREFIX = "mirror_"
    private const val GROUP_PREFIX = "group_"
    const val KEY_REPLY = "key_reply_text"
    const val EXTRA_NOTIF_KEY = "extra_notif_key"
    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    const val EXTRA_ACTION_INDEX = "extra_action_index"
    const val EXTRA_IS_MESSAGING = "extra_is_messaging"

    // Track notification ID per conversation key (reuse for stacking)
    private val notifIdMap = java.util.concurrent.ConcurrentHashMap<String, Int>()
    // Reverse mapping: notification ID → conversation key (for collision detection)
    private val notifIdReverse = java.util.concurrent.ConcurrentHashMap<Int, String>()
    // Track message history per conversation key for stacking
    // Use ArrayDeque for O(1) head removal when capping at 50 messages
    private val conversationMessages = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<Pair<String, String>>>()
    private val idLock = Any()
    private const val SUMMARY_TAG = "summary"
    private const val MAX_TRACKED_CONVERSATIONS = 200

    private val DEFAULT_VIBRATION = longArrayOf(0, 200, 100, 200)

    // Track recently replied conversation keys to suppress re-alert when app updates with your reply
    private val recentReplies = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val REPLY_SILENCE_WINDOW_MS = 5000L // 5 seconds

    /** Called by ReplyActivity after sending a reply to mark the conversation as recently replied */
    fun markReplied(notifKey: String) {
        // Map from notification key to conversation key
        val conversationKey = notifKeyToConversationKey[notifKey] ?: notifKey
        recentReplies[conversationKey] = System.currentTimeMillis()
        // Also store by raw notif key in case conversation key lookup fails later
        recentReplies[notifKey] = System.currentTimeMillis()
        // Clean up old entries
        val now = System.currentTimeMillis()
        recentReplies.entries.removeAll { now - it.value > REPLY_SILENCE_WINDOW_MS * 2 }
    }

    fun handleNotification(context: Context, messageEvent: MessageEvent) {
        val notifLog = NotificationLog(context)
        try {
            val json = JSONObject(String(messageEvent.data))
            val key = json.getString("key")
            val packageName = json.getString("package")
            val title = json.getString("title")
            val text = json.getString("text")
            val subText = json.optString("subText", "")
            val actionsArray = json.optJSONArray("actions")
            val iconBase64 = json.optString("icon", "")
            val pictureBase64 = json.optString("picture", "")
            val appLabel = json.optString("appLabel", "")

            val notifPriority = json.optInt("notifPriority", 1)
            val bigTextThreshold = json.optInt("bigTextThreshold", 40)
            val autoCancel = json.optBoolean("autoCancel", true)
            val showOpenButton = json.optBoolean("showOpenButton", true)
            val hasContentIntent = json.optBoolean("hasContentIntent", false)
            val showMuteButton = json.optBoolean("showMuteButton", true)
            val showSnoozeButton = json.optBoolean("showSnoozeButton", true)
            val snoozeDuration = json.optInt("snoozeDuration", 5)
            val muteDuration = json.optInt("muteDuration", 30)
            val defaultVibration = json.optString("defaultVibration", "0,200,100,200")
            val customVibrationPattern = json.optString("vibrationPattern", "")
            val isSilent = json.optBoolean("silent", false)
            val isOngoing = json.optBoolean("isOngoing", false)
            val hideContent = json.optBoolean("hideContent", false)
            val muteContinuation = json.optBoolean("muteContinuation", false)
            val vibrateOnly = json.optBoolean("vibrateOnly", false)
            val alertMode = json.optInt("alertMode", 0) // 0=sound, 1=vibrate, 2=mute
            // Deep link data for "Open on Watch" with sideloaded apps
            val notifTag = json.optString("notifTag", "")
            val shortcutId = json.optString("shortcutId", "")
            // Single SharedPreferences lookup for all watch-side settings
            val watchSettings = context.getSharedPreferences("notif_mirror_settings", Context.MODE_PRIVATE)

            // Respect both phone-side and watch-side history settings
            val phoneKeepHistory = json.optBoolean("keepHistory", true)
            val watchKeepHistory = watchSettings.getBoolean("keep_notification_history", true)
            val keepHistory = phoneKeepHistory && watchKeepHistory

            // Battery saver: check both phone-side and watch-side settings
            val phoneBatterySaverEnabled = json.optBoolean("batterySaverEnabled", false)
            val watchBatterySaverEnabled = watchSettings.getBoolean("battery_saver_enabled", false)
            val batterySaverEnabled = phoneBatterySaverEnabled || watchBatterySaverEnabled
            val phoneBatterySaverThreshold = json.optInt("batterySaverThreshold", 15)
            val watchBatterySaverThreshold = watchSettings.getInt("battery_saver_threshold", 15)
            val batterySaverThreshold = if (watchBatterySaverEnabled) watchBatterySaverThreshold else phoneBatterySaverThreshold
            if (batterySaverEnabled) {
                val batteryLevel = getWatchBatteryLevel(context)
                if (batteryLevel in 0 until batterySaverThreshold) {
                    if (keepHistory) {
                        notifLog.addEntry(packageName, title, text, "SKIPPED", "Battery saver: ${batteryLevel}% < ${batterySaverThreshold}%")
                    }
                    Log.d(TAG, "Skipping notification due to battery saver: ${batteryLevel}%")
                    return
                }
            }

            Log.d(TAG, "Received notification: $title - $text")

            // Respect watch-side mirroring toggle (synced from phone via DataClient)
            val watchMirroringEnabled = watchSettings.getBoolean("mirroring_enabled", true)
            if (!watchMirroringEnabled) {
                Log.d(TAG, "Skipping notification — mirroring disabled on watch")
                return
            }

            val muteManager = MuteManager(context)
            if (muteManager.isAppMuted(packageName)) {
                val shortPkg = packageName.split(".").lastOrNull() ?: packageName
                if (keepHistory) {
                    notifLog.addEntry(packageName, title, text, "MUTED", "App $shortPkg is temporarily muted")
                }
                Log.d(TAG, "Skipping muted app: $packageName")
                return
            }

            // Smart grouping: derive a conversation key from the notification
            // Uses conversationTitle (from EXTRA_CONVERSATION_TITLE) for stable grouping
            // of group chats where the title changes per sender (e.g. WhatsApp groups)
            val isMessagingStyle = json.optBoolean("isMessagingStyle", false)
            val conversationTitle = json.optString("conversationTitle", "")
            val conversationKey = deriveConversationKey(packageName, key, title, isMessagingStyle, conversationTitle)
            // Track reverse mapping for dismissals and package name for tile/summary cleanup
            notifKeyToConversationKey[key] = conversationKey
            convKeyToPackage[conversationKey] = packageName
            pruneTrackingMapsIfNeeded()

            // Reuse notification ID for same conversation key to stack messages
            // Synchronized to avoid race conditions across all shared mutable state
            // Check if this is a reply-triggered update (silence it)
            val isReplyUpdate = recentReplies[conversationKey]?.let {
                System.currentTimeMillis() - it < REPLY_SILENCE_WINDOW_MS
            } == true || recentReplies[key]?.let {
                System.currentTimeMillis() - it < REPLY_SILENCE_WINDOW_MS
            } == true
            if (isReplyUpdate) {
                Log.d(TAG, "Reply-triggered update detected for $conversationKey, will silence")
                // Clean up the reply marker
                recentReplies.remove(conversationKey)
                recentReplies.remove(key)
            }

            val (isUpdate, notifId, messages) = synchronized(idLock) {
                val existing = notifIdMap.containsKey(conversationKey)
                // Use deterministic hash-based ID so the same conversation always gets
                // the same notification ID, even after WearOS kills and restarts our process.
                // Without this, process restarts cause nextId to reset and assign different IDs
                // to existing conversations, leaving stale notifications as duplicates.
                val id = notifIdMap.getOrPut(conversationKey) {
                    var candidate = (conversationKey.hashCode() and 0x7FFFFFFF).coerceAtLeast(1000)
                    // Resolve hash collisions: if another conversation already uses this ID, increment
                    while (true) {
                        val existing = notifIdReverse.putIfAbsent(candidate, conversationKey)
                        if (existing == null || existing == conversationKey) break
                        candidate = ((candidate + 1) and 0x7FFFFFFF).coerceAtLeast(1000)
                    }
                    candidate
                }

                // Track conversation messages for stacking (cap at 50 to avoid unbounded memory growth)
                val msgList = conversationMessages.getOrPut(conversationKey) { ArrayDeque() }

                // If the phone sent pre-extracted conversation messages, use them as the
                // authoritative history (replaces any local state for this conversation).
                // This avoids duplicating the first message since the phone already includes
                // ALL messages in the conversation each time.
                val phoneConversationMsgs = json.optJSONArray("conversationMessages")
                if (phoneConversationMsgs != null && phoneConversationMsgs.length() > 0) {
                    msgList.clear()
                    for (i in 0 until phoneConversationMsgs.length()) {
                        val msgObj = phoneConversationMsgs.getJSONObject(i)
                        val sender = msgObj.optString("sender", title)
                        val msgText = msgObj.optString("text", "")
                        if (msgText.isNotEmpty()) {
                            msgList.addLast(Pair(sender, msgText))
                        }
                    }
                } else {
                    // Non-messaging app: append current message
                    val lastMsg = msgList.lastOrNull()
                    if (lastMsg == null || lastMsg.first != title || lastMsg.second != text) {
                        msgList.addLast(Pair(title, text))
                    }
                }
                while (msgList.size > 50) { msgList.removeFirst() }

                // Take a snapshot of messages for use outside the lock
                Triple(existing, id, ArrayList(msgList))
            }

            val actionCount = actionsArray?.length() ?: 0
            if (keepHistory) {
                notifLog.addEntry(
                    packageName, title, text, "RECEIVED", "$actionCount actions",
                    notifKey = key,
                    actionsJson = actionsArray?.toString() ?: "",
                    conversationKey = conversationKey
                )
            }

            val iconBitmap = if (iconBase64.isNotEmpty()) {
                try {
                    val bytes = Base64.decode(iconBase64, Base64.NO_WRAP)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decode icon", e)
                    null
                }
            } else null

            val pictureBitmap = if (pictureBase64.isNotEmpty()) {
                try {
                    val bytes = Base64.decode(pictureBase64, Base64.NO_WRAP)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decode picture", e)
                    null
                }
            } else null

            // Use phone-provided app label, fallback to cached or local resolution
            val resolvedAppLabel = if (appLabel.isNotEmpty()) {
                // Cache the phone-provided label for future use (e.g. when phone is disconnected)
                context.getSharedPreferences("app_labels", Context.MODE_PRIVATE)
                    .edit().putString(packageName, appLabel).apply()
                appLabel
            } else {
                getAppLabel(context, packageName)
            }

            showNotification(
                context, notifId, key, packageName, resolvedAppLabel, title, text, subText, actionsArray, iconBitmap,
                notifPriority, bigTextThreshold, autoCancel, showOpenButton,
                hasContentIntent = hasContentIntent, showMuteButton = showMuteButton,
                muteDuration = muteDuration, showSnoozeButton = showSnoozeButton, snoozeDuration = snoozeDuration,
                defaultVibration = defaultVibration, customVibrationPattern = customVibrationPattern,
                isSilent = isSilent, isOngoing = isOngoing,
                hideContent = hideContent, silentUpdate = (isUpdate && muteContinuation) || isReplyUpdate,
                conversationHistory = messages,
                vibrateOnly = vibrateOnly, alertMode = alertMode,
                conversationTitle = conversationTitle,
                pictureBitmap = pictureBitmap,
                isMessagingStyle = isMessagingStyle,
                notifTag = notifTag, shortcutId = shortcutId
            )

            if (!isUpdate) {
                NotificationTileService.incrementCount(context, packageName)
                NotificationComplicationService.incrementActiveCount(context)
            }

            // Sync complication settings from phone to watch
            val complicationSource = json.optString("complicationSource", "")
            if (complicationSource.isNotEmpty()) {
                watchSettings.edit()
                    .putString("complication_source", complicationSource)
                    .putString("complication_app", json.optString("complicationApp", ""))
                    .apply()
            }

            // Update the notification content complication
            NotificationComplicationService.updateComplication(context, resolvedAppLabel, packageName, title, text)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle notification", e)
            notifLog.addEntry("unknown", "Error", "", "ERROR", e.message ?: "Parse error")
        }
    }

    /**
     * Handle reconciliation message from phone after a full sync.
     * Removes any watch-side notifications whose keys are NOT in the phone's
     * active notification list — cleans up stale notifications that were missed
     * (e.g. dismissals lost during service restart).
     */
    fun handleReconciliation(context: Context, messageEvent: MessageEvent) {
        try {
            val json = JSONObject(String(messageEvent.data))
            val activeKeysArray = json.getJSONArray("activeKeys")
            val activeKeys = mutableSetOf<String>()
            for (i in 0 until activeKeysArray.length()) {
                activeKeys.add(activeKeysArray.getString(i))
            }

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Find notification keys tracked on watch that are no longer active on phone
            val staleEntries = synchronized(idLock) {
                // Deduplicate by conversation key to avoid double-decrementing counts
                // when multiple notification keys map to the same conversation
                val staleMap = mutableMapOf<String, Triple<String, Int, String>>() // convKey -> (convKey, notifId, packageName)
                for ((notifKey, convKey) in notifKeyToConversationKey) {
                    if (notifKey !in activeKeys && convKey !in staleMap) {
                        val notifId = notifIdMap[convKey]
                        if (notifId != null) {
                            val pkg = convKeyToPackage[convKey] ?: ""
                            staleMap[convKey] = Triple(convKey, notifId, pkg)
                        }
                    }
                }
                val stale = staleMap.values.toList()
                // Clean up the stale entries
                for ((convKey, notifId, _) in stale) {
                    notifIdMap.remove(convKey)
                    notifIdReverse.remove(notifId)
                    conversationMessages.remove(convKey)
                    convKeyToPackage.remove(convKey)
                }
                // Remove stale notifKey → convKey mappings
                notifKeyToConversationKey.entries.removeAll { it.key !in activeKeys }
                stale
            }

            for ((convKey, notifId, packageName) in staleEntries) {
                nm.cancel(notifId)
                if (packageName.isNotEmpty()) {
                    NotificationTileService.decrementCount(context, packageName)
                    NotificationComplicationService.decrementActiveCount(context)
                }
                Log.d(TAG, "Reconciliation: removed stale notification $notifId (convKey=$convKey)")
            }

            // Clean up orphaned summaries
            if (staleEntries.isNotEmpty()) {
                val packagesToCheck = staleEntries.map { it.third }.filter { it.isNotEmpty() }.distinct()
                for (pkg in packagesToCheck) {
                    val hasOtherNotifs = synchronized(idLock) {
                        convKeyToPackage.values.any { it == pkg }
                    }
                    if (!hasOtherNotifs) {
                        nm.cancel(SUMMARY_TAG, (pkg.hashCode() and 0x7FFFFFFF))
                    }
                }
            }

            Log.d(TAG, "Reconciliation complete: removed ${staleEntries.size} stale notifications, ${activeKeys.size} active on phone")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle reconciliation", e)
        }
    }

    fun handleDismissal(context: Context, messageEvent: MessageEvent) {
        try {
            val json = JSONObject(String(messageEvent.data))
            val key = json.getString("key")

            // Use conversation key mapping if available, otherwise fall back to raw key
            // Synchronized to avoid race conditions with handleNotification
            val (notifId, _, packageName) = synchronized(idLock) {
                val convKey = notifKeyToConversationKey.remove(key) ?: key
                val id = notifIdMap.remove(convKey) ?: return
                notifIdReverse.remove(id)
                conversationMessages.remove(convKey)
                val pkg = convKeyToPackage.remove(convKey) ?: json.optString("package", "")
                Triple(id, convKey, pkg)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId)

            // Cancel the per-app summary if no more notifications for this package
            if (packageName.isNotEmpty()) {
                val hasOtherNotifs = synchronized(idLock) {
                    convKeyToPackage.values.any { it == packageName }
                }
                if (!hasOtherNotifs) {
                    nm.cancel(SUMMARY_TAG, (packageName.hashCode() and 0x7FFFFFFF))
                }
            }
            if (packageName.isNotEmpty()) {
                NotificationTileService.decrementCount(context, packageName)
                NotificationComplicationService.decrementActiveCount(context)
            }

            Log.d(TAG, "Dismissed notification $notifId for key: $key")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle dismissal", e)
        }
    }

    private fun showNotification(
        context: Context,
        notifId: Int,
        notifKey: String,
        packageName: String,
        appLabel: String,
        title: String,
        text: String,
        subText: String,
        actionsArray: org.json.JSONArray?,
        iconBitmap: Bitmap?,
        notifPriority: Int = 1,
        bigTextThreshold: Int = 40,
        autoCancel: Boolean = true,
        showOpenButton: Boolean = true,
        hasContentIntent: Boolean = false,
        showMuteButton: Boolean = true,
        muteDuration: Int = 30,
        showSnoozeButton: Boolean = true,
        snoozeDuration: Int = 5,
        defaultVibration: String = "0,200,100,200",
        customVibrationPattern: String = "",
        isSilent: Boolean = false,
        isOngoing: Boolean = false,
        hideContent: Boolean = false,
        silentUpdate: Boolean = false,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        vibrateOnly: Boolean = false,
        alertMode: Int = 0, // 0=sound, 1=vibrate, 2=mute
        conversationTitle: String = "",
        pictureBitmap: Bitmap? = null,
        isMessagingStyle: Boolean = false,
        notifTag: String = "",
        shortcutId: String = ""
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channelId = CHANNEL_PREFIX + packageName
        val groupId = GROUP_PREFIX + packageName

        // Only create the channel group if it doesn't already exist
        if (nm.notificationChannelGroups.none { it.id == groupId }) {
            nm.createNotificationChannelGroup(NotificationChannelGroup(groupId, appLabel))
        }

        val vibrationPattern = getVibrationPattern(customVibrationPattern, defaultVibration)
        val importance = when (notifPriority) {
            0 -> NotificationManager.IMPORTANCE_DEFAULT
            -1 -> NotificationManager.IMPORTANCE_LOW
            else -> NotificationManager.IMPORTANCE_HIGH
        }
        // Use a stable per-app channel so users can customize sound/vibration
        // through OS notification channel settings (long-press notification → settings).
        // Only create the channel if it doesn't already exist to preserve user customizations.
        if (nm.getNotificationChannel(channelId) == null) {
            // Also clean up any old hash-based channels from previous versions
            val existingChannels = nm.notificationChannels
            for (ch in existingChannels) {
                if (ch.id.startsWith(channelId + "_")) {
                    nm.deleteNotificationChannel(ch.id)
                }
            }

            val channel = NotificationChannel(
                channelId,
                appLabel,
                importance
            ).apply {
                description = "Mirrored notifications from $appLabel"
                // Vibration is handled manually via Vibrator API
                enableVibration(false)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                this.group = groupId
            }
            nm.createNotificationChannel(channel)
        }

        val compatPriority = when (notifPriority) {
            0 -> NotificationCompat.PRIORITY_DEFAULT
            -1 -> NotificationCompat.PRIORITY_LOW
            else -> NotificationCompat.PRIORITY_HIGH
        }

        // Show just the notification title (not "AppLabel: Title") — app identity
        // is conveyed via subText and the per-app notification channel/group
        val displayTitle = if (hideContent) appLabel else title
        val displayText = if (hideContent) "Notification content hidden (phone locked)" else text

        // For mute-continuation: keep the SAME channel but suppress alerts via
        // setOnlyAlertOnce + setSilent so WearOS doesn't re-vibrate/sound
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(displayTitle)
            .setContentText(displayText)
            .setPriority(if (isSilent || vibrateOnly || silentUpdate || alertMode == 1 || alertMode == 2) NotificationCompat.PRIORITY_LOW else compatPriority)
            .setAutoCancel(if (isOngoing) false else autoCancel)
            .setOngoing(isOngoing)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(groupId)
            .setOnlyAlertOnce(silentUpdate)
            .setSilent(isSilent || vibrateOnly || silentUpdate || alertMode == 1 || alertMode == 2)

        // For ongoing notifications: set a DeleteIntent so we know when the user
        // dismisses it from the watch. This triggers a resend from the phone if
        // the notification is still active there.
        if (isOngoing) {
            val dismissIntent = Intent(context, OngoingDismissReceiver::class.java).apply {
                action = "com.notifmirror.wear.ONGOING_DISMISSED"
                putExtra(OngoingDismissReceiver.EXTRA_NOTIF_KEY, notifKey)
            }
            val dismissRequestCode = (notifKey + "ongoing_dismiss").hashCode() and 0x7FFFFFFF
            val dismissPendingIntent = PendingIntent.getBroadcast(
                context,
                dismissRequestCode,
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setDeleteIntent(dismissPendingIntent)
        }

        if (iconBitmap != null) {
            builder.setLargeIcon(iconBitmap)
        }

        // Always show app label in subText so users know which app the notification
        // is from (title no longer includes "AppLabel: " prefix)
        builder.setSubText(appLabel)

        // Stack conversation messages using MessagingStyle for better WearOS rendering
        if (!hideContent && conversationHistory.size > 1) {
            val recent = conversationHistory.takeLast(50)
            val selfPerson = Person.Builder().setName("You").build()
            val distinctSenders = recent.map { it.first }.distinct()
            val isGroupConversation = distinctSenders.size > 1
            val messagingStyle = NotificationCompat.MessagingStyle(selfPerson)
                .setGroupConversation(isGroupConversation)
                .setConversationTitle(if (isGroupConversation) (conversationTitle.ifEmpty { title }) else null)
            for ((idx, pair) in recent.withIndex()) {
                val (senderName, msgText) = pair
                // For self-messages ("You"), pass null as sender so MessagingStyle
                // renders them on the right side (outgoing message style)
                val sender = if (senderName == "You") null
                    else Person.Builder().setName(senderName).build()
                // Use incrementing timestamps so messages are ordered correctly
                messagingStyle.addMessage(msgText, System.currentTimeMillis() - (recent.size - idx) * 1000L, sender)
            }
            builder.setStyle(messagingStyle)
            builder.setNumber(conversationHistory.size)
            // Override contentTitle for MessagingStyle: use group name (or title for 1:1) with
            // app label in subText, so WearOS doesn't show "AppLabel: Title" redundantly
            builder.setContentTitle(if (conversationTitle.isNotEmpty()) conversationTitle else title)
            builder.setSubText(appLabel)
        } else if (!hideContent && pictureBitmap != null) {
            // BigPictureStyle for notifications with attached images (e.g. photo messages)
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(pictureBitmap)
                    .setSummaryText(text)
            )
        } else if (!hideContent && text.length > bigTextThreshold) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }

        if (actionsArray != null) {
            for (i in 0 until actionsArray.length()) {
                val actionJson = actionsArray.getJSONObject(i)
                val actionIndex = actionJson.getInt("index")
                val actionTitle = actionJson.getString("title")
                val hasRemoteInput = actionJson.getBoolean("hasRemoteInput")

                if (hasRemoteInput) {
                    // Use BroadcastReceiver instead of Activity so the app doesn't
                    // visually open when the user replies via inline RemoteInput
                    val replyIntent = Intent(context, ReplyBroadcastReceiver::class.java).apply {
                        action = "com.notifmirror.wear.REPLY"
                        putExtra(EXTRA_NOTIF_KEY, notifKey)
                        putExtra(EXTRA_NOTIFICATION_ID, notifId)
                        putExtra(EXTRA_ACTION_INDEX, actionIndex)
                        putExtra(EXTRA_IS_MESSAGING, isMessagingStyle)
                    }
                    val replyRequestCode = (notifKey + actionIndex).hashCode() and 0x7FFFFFFF
                    val replyPendingIntent = PendingIntent.getBroadcast(
                        context,
                        replyRequestCode,
                        replyIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )

                    val remoteInput = RemoteInput.Builder(KEY_REPLY)
                        .setLabel(actionTitle)
                        .build()

                    val replyAction = NotificationCompat.Action.Builder(
                        R.drawable.ic_reply,
                        actionTitle,
                        replyPendingIntent
                    )
                        .addRemoteInput(remoteInput)
                        .setAllowGeneratedReplies(true)
                        .build()

                    builder.addAction(replyAction)
                } else {
                    val actionIntent = Intent(context, ActionBroadcastReceiver::class.java).apply {
                        action = "com.notifmirror.wear.ACTION_TRIGGER"
                        putExtra(EXTRA_NOTIF_KEY, notifKey)
                        putExtra(EXTRA_NOTIFICATION_ID, notifId)
                        putExtra(EXTRA_ACTION_INDEX, actionIndex)
                    }
                    val actionRequestCode = (notifKey + actionIndex).hashCode() and 0x7FFFFFFF
                    val actionPendingIntent = PendingIntent.getBroadcast(
                        context,
                        actionRequestCode,
                        actionIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )

                    val notifAction = NotificationCompat.Action.Builder(
                        R.drawable.ic_action,
                        actionTitle,
                        actionPendingIntent
                    ).build()

                    builder.addAction(notifAction)
                }
            }
        }

        if (showOpenButton) {
            // "Open on Watch" — launches the app directly on watch if installed
            // For sideloaded apps, tries conversation-level deep linking first
            val launchIntent = getCompanionLaunchIntent(context, packageName, notifTag, shortcutId)
            if (launchIntent != null) {
                val openRequestCode = (notifKey + "open").hashCode() and 0x7FFFFFFF
                val openPendingIntent = PendingIntent.getActivity(
                    context,
                    openRequestCode,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(R.drawable.ic_action, "Open on Watch", openPendingIntent)
            }

            // "Open on Phone" — sends /open_app to phone which fires stored contentIntent
            // This opens the specific conversation/screen, works even if app isn't on watch
            if (hasContentIntent) {
                val openPhoneIntent = Intent(context, OpenOnPhoneBroadcastReceiver::class.java).apply {
                    action = "com.notifmirror.wear.OPEN_ON_PHONE"
                    putExtra(OpenOnPhoneBroadcastReceiver.EXTRA_NOTIF_KEY, notifKey)
                    putExtra(OpenOnPhoneBroadcastReceiver.EXTRA_PACKAGE_NAME, packageName)
                }
                val openPhoneRequestCode = (notifKey + "openphone").hashCode() and 0x7FFFFFFF
                val openPhonePendingIntent = PendingIntent.getBroadcast(
                    context,
                    openPhoneRequestCode,
                    openPhoneIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(R.drawable.ic_action, "Open on Phone", openPhonePendingIntent)
            }
        }

        if (showMuteButton) {
            val muteIntent = Intent(context, MuteBroadcastReceiver::class.java).apply {
                action = "com.notifmirror.wear.MUTE"
                putExtra(MuteBroadcastReceiver.EXTRA_PACKAGE_NAME, packageName)
                putExtra(MuteBroadcastReceiver.EXTRA_DURATION, muteDuration)
                putExtra(MuteBroadcastReceiver.EXTRA_NOTIFICATION_ID, notifId)
            }
            val muteRequestCode = (notifKey + "mute").hashCode() and 0x7FFFFFFF
            val mutePendingIntent = PendingIntent.getBroadcast(
                context,
                muteRequestCode,
                muteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val muteLabel = "Mute ${muteDuration}min"
            builder.addAction(R.drawable.ic_action, muteLabel, mutePendingIntent)
        }

        if (showSnoozeButton) {
            val snoozeIntent = Intent(context, SnoozeBroadcastReceiver::class.java).apply {
                action = "com.notifmirror.wear.SNOOZE"
                putExtra(SnoozeBroadcastReceiver.EXTRA_NOTIF_KEY, notifKey)
                putExtra(SnoozeBroadcastReceiver.EXTRA_NOTIFICATION_ID, notifId)
                putExtra(SnoozeBroadcastReceiver.EXTRA_SNOOZE_DURATION_MS, snoozeDuration.toLong() * 60000L)
                putExtra(SnoozeBroadcastReceiver.EXTRA_PACKAGE_NAME, packageName)
            }
            val snoozeRequestCode = (notifKey + "snooze").hashCode() and 0x7FFFFFFF
            val snoozePendingIntent = PendingIntent.getBroadcast(
                context,
                snoozeRequestCode,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val snoozeLabel = "Snooze ${snoozeDuration}min"
            builder.addAction(R.drawable.ic_action, snoozeLabel, snoozePendingIntent)
        }

        nm.notify(notifId, builder.build())

        // Manually vibrate if not a silent update, not isSilent, and not low priority
        // vibrateOnly mode: suppress sound (via low-priority notification) but still vibrate
        // Post with a short delay so the system finishes processing the notification
        // before we trigger our own vibration (avoids the OS canceling it).
        // alertMode: 0=sound (vibrate+sound), 1=vibrate only, 2=mute (no vibrate, no sound)
        val shouldVibrate = ((!silentUpdate && !isSilent && notifPriority != -1 && alertMode != 2) ||
            (vibrateOnly && !silentUpdate && !isSilent && alertMode != 2) ||
            (alertMode == 1 && !silentUpdate && !isSilent && notifPriority != -1))
        if (shouldVibrate) {
            Handler(Looper.getMainLooper()).postDelayed({
                vibrateManually(context, vibrationPattern)
            }, 150)
        }

        // Create/update summary notification for the per-app group.
        // Uses a tag ("summary") to prevent ID collision with regular notification IDs.
        val summaryId = (packageName.hashCode() and 0x7FFFFFFF)
        val summaryBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(appLabel)
            .setContentText(title)
            .setSubText(appLabel)
            .setGroup(groupId)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setSilent(true)
            .setStyle(NotificationCompat.InboxStyle()
                .setSummaryText(appLabel))
        nm.notify(SUMMARY_TAG, summaryId, summaryBuilder.build())
    }

    /**
     * Manually vibrate the watch using Vibrator API.
     * This bypasses the notification channel vibration which Android caches.
     * Uses AudioAttributes so the vibration is routed correctly on WearOS
     * and isn't suppressed by DND / notification-priority filters.
     */
    private fun vibrateManually(context: Context, pattern: LongArray) {
        try {
            if (pattern.all { it == 0L }) return
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val effect = VibrationEffect.createWaveform(pattern, -1)
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            vibrator.vibrate(effect, attrs)
            Log.d(TAG, "Vibrated with pattern: ${pattern.joinToString(",")}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to vibrate manually", e)
        }
    }

    // Cache parsed vibration patterns to avoid re-parsing the same string on every notification
    private val vibrationPatternCache = java.util.concurrent.ConcurrentHashMap<String, LongArray>()

    private fun getVibrationPattern(
        customPattern: String = "",
        defaultPattern: String = "0,200,100,200"
    ): LongArray {
        val patternStr = if (customPattern.isNotEmpty()) customPattern else defaultPattern
        return vibrationPatternCache.getOrPut(patternStr) {
            parseVibrationPattern(patternStr) ?: DEFAULT_VIBRATION
        }
    }

    private fun parseVibrationPattern(pattern: String): LongArray? {
        return try {
            val parts = pattern.split(",").map { it.trim().toLong() }
            if (parts.size >= 2) parts.toLongArray() else null
        } catch (_: Exception) {
            null
        }
    }

    private fun getCompanionLaunchIntent(
        context: Context,
        phonePackageName: String,
        notifTag: String = "",
        shortcutId: String = ""
    ): Intent? {
        val pm = context.packageManager

        // Try conversation-level deep link first (for sideloaded apps)
        val deepLinkIntent = getDeepLinkIntent(context, phonePackageName, notifTag, shortcutId)
        if (deepLinkIntent != null) {
            Log.d(TAG, "Using deep link for $phonePackageName (tag=$notifTag, shortcutId=$shortcutId)")
            return deepLinkIntent
        }

        val directIntent = pm.getLaunchIntentForPackage(phonePackageName)
        if (directIntent != null) {
            directIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            return directIntent
        }
        val watchPackage = KNOWN_WATCH_PACKAGES[phonePackageName]
        if (watchPackage != null) {
            val mappedIntent = pm.getLaunchIntentForPackage(watchPackage)
            if (mappedIntent != null) {
                mappedIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                return mappedIntent
            }
        }
        return null
    }

    /**
     * Try to construct a conversation-level deep link intent for sideloaded apps.
     * Uses notification tag and shortcutId sent from the phone to build app-specific
     * deep link URIs that open the specific conversation rather than just the app.
     *
     * Falls back to null if the app isn't installed or the deep link can't be resolved.
     */
    private fun getDeepLinkIntent(
        context: Context,
        packageName: String,
        notifTag: String,
        shortcutId: String
    ): Intent? {
        if (notifTag.isEmpty() && shortcutId.isEmpty()) return null

        val pm = context.packageManager
        // Only try deep linking if the app is actually installed on the watch
        try {
            pm.getPackageInfo(packageName, 0)
        } catch (_: Exception) {
            return null
        }

        val uri = when (packageName) {
            "com.whatsapp", "com.whatsapp.w4b" -> {
                // WhatsApp sets notification tag to JID (e.g. "1234567890@s.whatsapp.net")
                if (notifTag.contains("@")) {
                    Uri.parse("whatsapp://send?jid=$notifTag")
                } else null
            }
            "com.facebook.orca" -> {
                // Messenger: use shortcutId or tag as thread identifier
                val threadId = shortcutId.ifEmpty { notifTag }
                if (threadId.isNotEmpty()) {
                    Uri.parse("fb-messenger://thread/$threadId")
                } else null
            }
            "org.telegram.messenger", "org.telegram.messenger.web",
            "org.thunderdog.challegram", "nekox.messenger" -> {
                // Telegram and forks: try tg:// scheme with chat identifier
                val chatId = notifTag.ifEmpty { shortcutId }
                if (chatId.isNotEmpty()) {
                    Uri.parse("tg://openmessage?chat_id=$chatId")
                } else null
            }
            "com.discord" -> {
                // Discord: try discord:// scheme with channel info from tag
                val channelId = notifTag.ifEmpty { shortcutId }
                if (channelId.isNotEmpty()) {
                    Uri.parse("discord://discord.com/channels/$channelId")
                } else null
            }
            "com.instagram.android" -> {
                // Instagram: try opening thread via shortcutId
                if (shortcutId.isNotEmpty()) {
                    Uri.parse("instagram://direct_thread?threadId=$shortcutId")
                } else null
            }
            else -> null
        }

        if (uri != null) {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            // Verify the intent can be resolved before returning
            if (intent.resolveActivity(pm) != null) {
                return intent
            }
            Log.d(TAG, "Deep link $uri not resolvable for $packageName, falling back to launch")
        }

        return null
    }

    private fun getAppLabel(context: Context, packageName: String): String {
        // Check cached labels from phone first
        val cached = context.getSharedPreferences("app_labels", Context.MODE_PRIVATE)
            .getString(packageName, null)
        if (!cached.isNullOrEmpty()) return cached

        // Fallback to hardcoded mapping for common apps
        return when {
            packageName.contains("whatsapp") -> "WhatsApp"
            packageName.contains("telegram") -> "Telegram"
            packageName.contains("instagram") -> "Instagram"
            packageName.contains("messenger") -> "Messenger"
            packageName.contains("twitter") || packageName.contains("x.android") -> "X"
            packageName.contains("gmail") -> "Gmail"
            packageName.contains("discord") -> "Discord"
            packageName.contains("slack") -> "Slack"
            packageName.contains("signal") -> "Signal"
            packageName.contains("sms") || packageName.contains("mms") -> "Messages"
            else -> {
                val parts = packageName.split(".")
                parts.lastOrNull()?.replaceFirstChar { it.uppercase() } ?: packageName
            }
        }
    }

    private fun getWatchBatteryLevel(context: Context): Int {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (scale > 0) (level * 100 / scale) else -1
    }

    /**
     * Derive a conversation-level grouping key from notification metadata.
     *
     * Always uses the notification key (sbn.key from the phone) which is the
     * canonical, stable identifier for a notification. Each conversation in
     * messaging apps (WhatsApp, Telegram, etc.) has exactly one notification
     * with a stable key that persists across content updates.
     *
     * Previous approach used conversationTitle/title, but these are unstable:
     * WhatsApp includes dynamic content like "(14 messages)", "person replied
     * to you", sender name, etc. — causing a different key per update and
     * leaving stale notifications as duplicates.
     */
    private fun deriveConversationKey(
        packageName: String,
        notifKey: String,
        title: String,
        isMessagingStyle: Boolean,
        conversationTitle: String = ""
    ): String {
        // The notification key (sbn.key) is always stable for the same conversation.
        // It only changes if the notification is dismissed and recreated.
        return notifKey
    }

    // Reverse lookup: find conversation key from notification key
    private val notifKeyToConversationKey = java.util.concurrent.ConcurrentHashMap<String, String>()
    // Track package name per conversation key (needed for tile counts and summary cleanup)
    private val convKeyToPackage = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Prune tracking maps if they grow too large to keep memory bounded */
    private fun pruneTrackingMapsIfNeeded() {
        if (notifKeyToConversationKey.size > MAX_TRACKED_CONVERSATIONS * 2) {
            // Only keep entries that have a corresponding notifIdMap entry
            val activeConvKeys = synchronized(idLock) { notifIdMap.keys.toSet() }
            notifKeyToConversationKey.entries.removeAll { it.value !in activeConvKeys }
            convKeyToPackage.entries.removeAll { it.key !in activeConvKeys }
            conversationMessages.entries.removeAll { it.key !in activeConvKeys }
        }
        // Also prune notifIdMap/notifIdReverse if they grow too large
        if (notifIdMap.size > MAX_TRACKED_CONVERSATIONS) {
            synchronized(idLock) {
                // Keep the most recent entries by removing excess from the maps
                val excess = notifIdMap.size - MAX_TRACKED_CONVERSATIONS
                if (excess > 0) {
                    val iter = notifIdMap.entries.iterator()
                    var removed = 0
                    while (iter.hasNext() && removed < excess) {
                        val entry = iter.next()
                        iter.remove()
                        notifIdReverse.remove(entry.value)
                        conversationMessages.remove(entry.key)
                        convKeyToPackage.remove(entry.key)
                        removed++
                    }
                }
            }
        }
    }

    private val KNOWN_WATCH_PACKAGES = mapOf(
        "com.google.android.apps.messaging" to "com.google.android.apps.messaging",
        "com.google.android.gm" to "com.google.android.gm",
        "com.whatsapp" to "com.whatsapp",
        "com.whatsapp.w4b" to "com.whatsapp.w4b",
        "org.telegram.messenger" to "org.telegram.messenger",
        "com.facebook.orca" to "com.facebook.orca",
        "com.spotify.music" to "com.spotify.music",
        "com.google.android.apps.maps" to "com.google.android.apps.maps",
        "com.google.android.apps.fitness" to "com.google.android.apps.fitness",
        "com.strava" to "com.strava",
        "com.discord" to "com.discord"
    )
}
