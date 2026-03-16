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

    // Track notification ID per conversation key (reuse for stacking)
    private val notifIdMap = java.util.concurrent.ConcurrentHashMap<String, Int>()
    // Track message history per conversation key for stacking
    private val conversationMessages = java.util.concurrent.ConcurrentHashMap<String, MutableList<Pair<String, String>>>()
    private val nextId = java.util.concurrent.atomic.AtomicInteger(1000)
    private val idLock = Any()
    private const val SUMMARY_ID_OFFSET = 500000
    private const val MAX_TRACKED_CONVERSATIONS = 200

    private val DEFAULT_VIBRATION = longArrayOf(0, 200, 100, 200)

    // Track recently replied conversation keys to suppress re-alert when app updates with your reply
    private val recentReplies = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val REPLY_SILENCE_WINDOW_MS = 15000L // 15 seconds

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
            val appLabel = json.optString("appLabel", "")

            val notifPriority = json.optInt("notifPriority", 1)
            val bigTextThreshold = json.optInt("bigTextThreshold", 40)
            val autoCancel = json.optBoolean("autoCancel", true)
            val showOpenButton = json.optBoolean("showOpenButton", true)
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
            // Track reverse mapping for dismissals
            notifKeyToConversationKey[key] = conversationKey
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
                val id = notifIdMap.getOrPut(conversationKey) { nextId.getAndIncrement() }

                // Track conversation messages for stacking (cap at 20 to avoid unbounded memory growth)
                val msgList = conversationMessages.getOrPut(conversationKey) { mutableListOf() }

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
                            msgList.add(Pair(sender, msgText))
                        }
                    }
                } else {
                    // Non-messaging app: append current message
                    val lastMsg = msgList.lastOrNull()
                    if (lastMsg == null || lastMsg.first != title || lastMsg.second != text) {
                        msgList.add(Pair(title, text))
                    }
                }
                while (msgList.size > 20) { msgList.removeAt(0) }

                // Take a snapshot of messages for use outside the lock
                Triple(existing, id, ArrayList(msgList))
            }

            val actionCount = actionsArray?.length() ?: 0
            if (keepHistory) {
                notifLog.addEntry(
                    packageName, title, text, "RECEIVED", "$actionCount actions",
                    notifKey = key,
                    actionsJson = actionsArray?.toString() ?: ""
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
                notifPriority, bigTextThreshold, autoCancel, showOpenButton, showMuteButton,
                muteDuration = muteDuration, showSnoozeButton = showSnoozeButton, snoozeDuration = snoozeDuration,
                defaultVibration = defaultVibration, customVibrationPattern = customVibrationPattern,
                isSilent = isSilent, isOngoing = isOngoing,
                hideContent = hideContent, silentUpdate = (isUpdate && muteContinuation) || isReplyUpdate,
                conversationHistory = messages,
                vibrateOnly = vibrateOnly
            )

            if (!isUpdate) NotificationTileService.incrementCount(context, packageName)

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

    fun handleDismissal(context: Context, messageEvent: MessageEvent) {
        try {
            val json = JSONObject(String(messageEvent.data))
            val key = json.getString("key")

            // Use conversation key mapping if available, otherwise fall back to raw key
            // Synchronized to avoid race conditions with handleNotification
            val (notifId, _, packageName) = synchronized(idLock) {
                val convKey = notifKeyToConversationKey.remove(key) ?: key
                val id = notifIdMap.remove(convKey) ?: return
                conversationMessages.remove(convKey)
                Triple(id, convKey, json.optString("package", ""))
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId)

            // Cancel the per-app summary if no more notifications for this package
            if (packageName.isNotEmpty()) {
                val hasOtherNotifs = synchronized(idLock) {
                    notifIdMap.keys.any { k ->
                        k == packageName || k.startsWith("$packageName:")
                    }
                }
                if (!hasOtherNotifs) {
                    nm.cancel(packageName.hashCode() + SUMMARY_ID_OFFSET)
                }
            }
            if (packageName.isNotEmpty()) {
                NotificationTileService.decrementCount(context, packageName)
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
        vibrateOnly: Boolean = false
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

        val displayTitle = if (hideContent) appLabel else "$appLabel: $title"
        val displayText = if (hideContent) "Notification content hidden (phone locked)" else text

        // For mute-continuation: keep the SAME channel but suppress alerts via
        // setOnlyAlertOnce + setSilent so WearOS doesn't re-vibrate/sound
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(displayTitle)
            .setContentText(displayText)
            .setPriority(if (isSilent || vibrateOnly || silentUpdate) NotificationCompat.PRIORITY_LOW else compatPriority)
            .setAutoCancel(if (isOngoing) false else autoCancel)
            .setOngoing(isOngoing)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(groupId)
            .setOnlyAlertOnce(silentUpdate)
            .setSilent(silentUpdate)

        if (iconBitmap != null) {
            builder.setLargeIcon(iconBitmap)
        }

        if (hideContent) {
            builder.setSubText(appLabel)
        } else if (subText.isNotEmpty()) {
            builder.setSubText(subText)
        } else {
            builder.setSubText(appLabel)
        }

        // Stack conversation messages using MessagingStyle for better WearOS rendering
        if (!hideContent && conversationHistory.size > 1) {
            val recent = conversationHistory.takeLast(20)
            val selfPerson = Person.Builder().setName("You").build()
            val distinctSenders = recent.map { it.first }.distinct()
            val isGroupConversation = distinctSenders.size > 1
            val messagingStyle = NotificationCompat.MessagingStyle(selfPerson)
                .setGroupConversation(isGroupConversation)
                .setConversationTitle(if (isGroupConversation) title else null)
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
            // Override contentTitle for MessagingStyle: use title (chat name) with
            // app label in subText, so WearOS doesn't show "AppLabel: Title" redundantly
            builder.setContentTitle(title)
            builder.setSubText(appLabel)
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
                    val replyIntent = Intent(context, ReplyActivity::class.java).apply {
                        putExtra(EXTRA_NOTIF_KEY, notifKey)
                        putExtra(EXTRA_NOTIFICATION_ID, notifId)
                        putExtra(EXTRA_ACTION_INDEX, actionIndex)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    val replyRequestCode = (notifKey + actionIndex).hashCode() and 0x7FFFFFFF
                    val replyPendingIntent = PendingIntent.getActivity(
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
            val launchIntent = getCompanionLaunchIntent(context, packageName)
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

        nm.notify(notifId, builder.build())

        // Manually vibrate if not a silent update, not isSilent, and not low priority
        // vibrateOnly mode: suppress sound (via low-priority notification) but still vibrate
        if (!silentUpdate && !isSilent && notifPriority != -1) {
            vibrateManually(context, vibrationPattern)
        } else if (vibrateOnly && !silentUpdate) {
            vibrateManually(context, vibrationPattern)
        }

        // Create/update summary notification for the per-app group
        val summaryId = packageName.hashCode() + SUMMARY_ID_OFFSET
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
        nm.notify(summaryId, summaryBuilder.build())
    }

    /**
     * Manually vibrate the watch using Vibrator API.
     * This bypasses the notification channel vibration which Android caches.
     */
    private fun vibrateManually(context: Context, pattern: LongArray) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to vibrate manually", e)
        }
    }

    private fun getVibrationPattern(
        customPattern: String = "",
        defaultPattern: String = "0,200,100,200"
    ): LongArray {
        if (customPattern.isNotEmpty()) {
            return parseVibrationPattern(customPattern) ?: DEFAULT_VIBRATION
        }
        return parseVibrationPattern(defaultPattern) ?: DEFAULT_VIBRATION
    }

    private fun parseVibrationPattern(pattern: String): LongArray? {
        return try {
            val parts = pattern.split(",").map { it.trim().toLong() }
            if (parts.size >= 2) parts.toLongArray() else null
        } catch (_: Exception) {
            null
        }
    }

    private fun getCompanionLaunchIntent(context: Context, phonePackageName: String): Intent? {
        val pm = context.packageManager
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
     * For messaging apps (detected via MessagingStyle on the phone side),
     * uses conversationTitle (from EXTRA_CONVERSATION_TITLE) when available
     * for stable grouping — this is the group/chat name that stays constant
     * even when different people send messages (e.g. WhatsApp group name).
     * Falls back to notification title, then notification key.
     */
    private fun deriveConversationKey(
        packageName: String,
        notifKey: String,
        title: String,
        isMessagingStyle: Boolean,
        conversationTitle: String = ""
    ): String {
        return if (isMessagingStyle) {
            when {
                // Prefer conversationTitle — stable across sender changes
                // e.g. "HHH GNG" stays the same regardless of who sent the message
                conversationTitle.isNotEmpty() -> "$packageName:$conversationTitle"
                // Fallback to title if no conversationTitle (1:1 chats)
                title.isNotEmpty() -> "$packageName:$title"
                else -> notifKey
            }
        } else {
            // Non-messaging apps: use the original notification key
            notifKey
        }
    }

    // Reverse lookup: find conversation key from notification key
    private val notifKeyToConversationKey = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Prune tracking maps if they grow too large to keep memory bounded */
    private fun pruneTrackingMapsIfNeeded() {
        if (notifKeyToConversationKey.size > MAX_TRACKED_CONVERSATIONS * 2) {
            // Only keep entries that have a corresponding notifIdMap entry
            val activeConvKeys = synchronized(idLock) { notifIdMap.keys.toSet() }
            notifKeyToConversationKey.entries.removeAll { it.value !in activeConvKeys }
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
