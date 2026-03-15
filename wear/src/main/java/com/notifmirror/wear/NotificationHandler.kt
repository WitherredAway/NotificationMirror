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
import android.net.Uri
import android.os.BatteryManager
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
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
    private val notifIdMap = mutableMapOf<String, Int>()
    // Track message history per conversation key for stacking
    private val conversationMessages = mutableMapOf<String, MutableList<Pair<String, String>>>()
    private var nextId = 1000
    private const val SUMMARY_ID_OFFSET = 500000

    private val DEFAULT_VIBRATION = longArrayOf(0, 200, 100, 200)

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
            val customSoundUri = json.optString("soundUri", "")
            val isSilent = json.optBoolean("silent", false)
            val hideContent = json.optBoolean("hideContent", false)
            val muteContinuation = json.optBoolean("muteContinuation", false)
            // Respect both phone-side and watch-side history settings
            val phoneKeepHistory = json.optBoolean("keepHistory", true)
            val watchKeepHistory = context.getSharedPreferences("notif_mirror_settings", Context.MODE_PRIVATE)
                .getBoolean("keep_notification_history", true)
            val keepHistory = phoneKeepHistory && watchKeepHistory

            // Battery saver: check both phone-side and watch-side settings
            val watchSettings = context.getSharedPreferences("notif_mirror_settings", Context.MODE_PRIVATE)
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
            // For messaging apps, group by title (sender/chat name) within the same app
            // This groups "John" messages together even if they have different notification keys
            val conversationKey = deriveConversationKey(packageName, key, title)
            // Track reverse mapping for dismissals
            notifKeyToConversationKey[key] = conversationKey

            // Reuse notification ID for same conversation key to stack messages
            val isUpdate = notifIdMap.containsKey(conversationKey)
            val notifId = notifIdMap.getOrPut(conversationKey) { nextId++ }

            // Track conversation messages for stacking (cap at 20 to avoid unbounded memory growth)
            val msgList = conversationMessages.getOrPut(conversationKey) { mutableListOf() }
            msgList.add(Pair(title, text))
            while (msgList.size > 20) { msgList.removeAt(0) }

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

            // Use phone-provided app label, fallback to local resolution
            val resolvedAppLabel = if (appLabel.isNotEmpty()) appLabel else getAppLabel(packageName)

            val messages = conversationMessages[conversationKey] ?: mutableListOf(Pair(title, text))

            showNotification(
                context, notifId, key, packageName, resolvedAppLabel, title, text, subText, actionsArray, iconBitmap,
                notifPriority, bigTextThreshold, autoCancel, showOpenButton, showMuteButton,
                muteDuration = muteDuration, showSnoozeButton = showSnoozeButton, snoozeDuration = snoozeDuration,
                defaultVibration = defaultVibration, customVibrationPattern = customVibrationPattern,
                customSoundUri = customSoundUri, isSilent = isSilent,
                hideContent = hideContent, silentUpdate = isUpdate && muteContinuation,
                conversationHistory = messages
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
            val conversationKey = notifKeyToConversationKey.remove(key) ?: key
            val notifId = notifIdMap.remove(conversationKey) ?: return
            conversationMessages.remove(conversationKey)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId)

            val packageName = json.optString("package", "")
            // Cancel the per-app summary if no more notifications for this package
            if (packageName.isNotEmpty()) {
                val hasOtherNotifs = notifIdMap.keys.any { k ->
                    k == packageName || k.startsWith("$packageName:")
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
        customSoundUri: String = "",
        isSilent: Boolean = false,
        hideContent: Boolean = false,
        silentUpdate: Boolean = false,
        conversationHistory: List<Pair<String, String>> = emptyList()
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channelId = CHANNEL_PREFIX + packageName
        val groupId = GROUP_PREFIX + packageName

        val group = NotificationChannelGroup(groupId, appLabel)
        nm.createNotificationChannelGroup(group)

        val vibrationPattern = getVibrationPattern(customVibrationPattern, defaultVibration)
        val importance = when (notifPriority) {
            0 -> NotificationManager.IMPORTANCE_DEFAULT
            -1 -> NotificationManager.IMPORTANCE_LOW
            else -> NotificationManager.IMPORTANCE_HIGH
        }
        // Include hashes of vibration, sound, importance, and silent flag in channel ID
        // so that any settings change creates a fresh channel (Android caches channel settings)
        val vibHash = vibrationPattern.contentHashCode()
        val soundHash = if (customSoundUri.isNotEmpty()) customSoundUri.hashCode() else 0
        val importanceHash = if (isSilent) -1 else importance
        val settingsSuffix = "_v${vibHash}_s${soundHash}_i${importanceHash}"
        val effectiveChannelId = channelId + settingsSuffix

        // Delete any old channels for this app so settings always take effect
        val existingChannels = nm.notificationChannels
        for (ch in existingChannels) {
            val isThisAppChannel = ch.id == channelId || ch.id.startsWith(channelId + "_")
            if (isThisAppChannel && ch.id != effectiveChannelId) {
                nm.deleteNotificationChannel(ch.id)
            }
        }

        val channel = NotificationChannel(
            effectiveChannelId,
            appLabel,
            if (isSilent) NotificationManager.IMPORTANCE_LOW else importance
        ).apply {
            description = "Mirrored notifications from $appLabel"
            enableVibration(true)
            this.vibrationPattern = vibrationPattern
            this.group = groupId
            if (customSoundUri.isNotEmpty()) {
                // Phone content:// URIs won't resolve on the watch.
                // Use the system default notification sound as the channel sound
                // so Android treats this as a distinct sound channel vs the silent/default ones.
                val watchSoundUri = android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
                setSound(
                    watchSoundUri,
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        }
        nm.createNotificationChannel(channel)

        val compatPriority = when (notifPriority) {
            0 -> NotificationCompat.PRIORITY_DEFAULT
            -1 -> NotificationCompat.PRIORITY_LOW
            else -> NotificationCompat.PRIORITY_HIGH
        }

        val displayTitle = if (hideContent) appLabel else "$appLabel: $title"
        val displayText = if (hideContent) "Notification content hidden (phone locked)" else text

        // For mute-continuation: keep the SAME channel but suppress alerts via
        // setOnlyAlertOnce + setSilent so WearOS doesn't re-vibrate/sound
        val builder = NotificationCompat.Builder(context, effectiveChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(displayTitle)
            .setContentText(displayText)
            .setPriority(if (isSilent || silentUpdate) NotificationCompat.PRIORITY_LOW else compatPriority)
            .setAutoCancel(autoCancel)
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

        // Stack conversation messages if multiple exist
        if (!hideContent && conversationHistory.size > 1) {
            // Show all stacked messages separated by newlines using BigTextStyle
            val recent = conversationHistory.takeLast(20)
            val stackedText = recent.joinToString("\n") { (_, msg) -> msg }
            builder.setContentText("${conversationHistory.size} messages")
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(stackedText)
                .setSummaryText("${conversationHistory.size} messages"))
            builder.setNumber(conversationHistory.size)
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
                    val replyPendingIntent = PendingIntent.getActivity(
                        context,
                        notifId * 100 + actionIndex,
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
                    val actionPendingIntent = PendingIntent.getBroadcast(
                        context,
                        notifId * 100 + actionIndex,
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
                val openPendingIntent = PendingIntent.getActivity(
                    context,
                    notifId * 100 + 99,
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
            val snoozePendingIntent = PendingIntent.getBroadcast(
                context,
                notifId * 100 + 98,
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
            val mutePendingIntent = PendingIntent.getBroadcast(
                context,
                notifId * 100 + 97,
                muteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val muteLabel = "Mute ${muteDuration}min"
            builder.addAction(R.drawable.ic_action, muteLabel, mutePendingIntent)
        }

        nm.notify(notifId, builder.build())

        // Create/update summary notification for the per-app group
        val summaryId = packageName.hashCode() + SUMMARY_ID_OFFSET
        val summaryBuilder = NotificationCompat.Builder(context, effectiveChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(appLabel)
            .setContentText(title)
            .setSubText(appLabel)
            .setGroup(groupId)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setSilent(silentUpdate)
            .setStyle(NotificationCompat.InboxStyle()
                .setSummaryText(appLabel))
        nm.notify(summaryId, summaryBuilder.build())
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

    private fun getAppLabel(packageName: String): String {
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
     * For messaging apps, groups by sender/chat name (title) within the same app.
     * For other apps, falls back to the notification key.
     */
    private fun deriveConversationKey(packageName: String, notifKey: String, title: String): String {
        // Known messaging apps where title = sender/conversation name
        val isMessagingApp = packageName.contains("whatsapp") ||
            packageName.contains("telegram") ||
            packageName.contains("messenger") ||
            packageName.contains("signal") ||
            packageName.contains("discord") ||
            packageName.contains("slack") ||
            packageName.contains("instagram") ||
            packageName.contains("sms") ||
            packageName.contains("mms") ||
            packageName.contains("messaging") ||
            packageName.contains("gmail")

        return if (isMessagingApp && title.isNotEmpty()) {
            // Group by app + sender/conversation title
            "$packageName:$title"
        } else {
            // Non-messaging apps: use the original notification key
            notifKey
        }
    }

    // Reverse lookup: find conversation key from notification key
    private val notifKeyToConversationKey = mutableMapOf<String, String>()

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
