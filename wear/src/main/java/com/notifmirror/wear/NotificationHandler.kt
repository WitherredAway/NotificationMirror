package com.notifmirror.wear

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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

    // Track ALL notification IDs per key so we can dismiss them all
    private val notifIdsMap = mutableMapOf<String, MutableList<Int>>()
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
            val muteDuration = json.optInt("muteDuration", 30)
            val defaultVibration = json.optString("defaultVibration", "0,200,100,200")
            val customVibrationPattern = json.optString("vibrationPattern", "")
            val customSoundUri = json.optString("soundUri", "")
            val isSilent = json.optBoolean("silent", false)

            Log.d(TAG, "Received notification: $title - $text")

            val muteManager = MuteManager(context)
            if (muteManager.isAppMuted(packageName)) {
                val shortPkg = packageName.split(".").lastOrNull() ?: packageName
                notifLog.addEntry(packageName, title, text, "MUTED", "App $shortPkg is temporarily muted")
                Log.d(TAG, "Skipping muted app: $packageName")
                return
            }

            // Always assign a new unique notification ID for every message
            // so even identical messages show separately
            val notifId = nextId++
            notifIdsMap.getOrPut(key) { mutableListOf() }.add(notifId)

            val actionCount = actionsArray?.length() ?: 0
            notifLog.addEntry(
                packageName, title, text, "RECEIVED", "$actionCount actions",
                notifKey = key,
                actionsJson = actionsArray?.toString() ?: ""
            )

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

            showNotification(
                context, notifId, key, packageName, resolvedAppLabel, title, text, subText, actionsArray, iconBitmap,
                notifPriority, bigTextThreshold, autoCancel, showOpenButton, showMuteButton,
                muteDuration, defaultVibration, customVibrationPattern, customSoundUri, isSilent
            )

            NotificationTileService.incrementCount(context, packageName)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle notification", e)
            notifLog.addEntry("unknown", "Error", "", "ERROR", e.message ?: "Parse error")
        }
    }

    fun handleDismissal(context: Context, messageEvent: MessageEvent) {
        try {
            val json = JSONObject(String(messageEvent.data))
            val key = json.getString("key")

            val ids = notifIdsMap.remove(key)
            if (ids == null || ids.isEmpty()) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // Cancel all stacked notifications for this conversation
            for (id in ids) {
                nm.cancel(id)
            }

            val packageName = json.optString("package", "")
            // Cancel the per-app summary if no more notifications for this package
            if (packageName.isNotEmpty()) {
                val hasOtherNotifs = notifIdsMap.keys.any { k ->
                    k.contains(packageName)
                }
                if (!hasOtherNotifs) {
                    nm.cancel(packageName.hashCode() + SUMMARY_ID_OFFSET)
                }
            }
            if (packageName.isNotEmpty()) {
                NotificationTileService.decrementCount(context, packageName)
            }

            Log.d(TAG, "Dismissed ${ids.size} notifications for key: $key")
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
        defaultVibration: String = "0,200,100,200",
        customVibrationPattern: String = "",
        customSoundUri: String = "",
        isSilent: Boolean = false
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
        // Use a unique channel ID when custom sound is set so the sound setting takes effect
        val soundSuffix = if (customSoundUri.isNotEmpty()) "_sound_${customSoundUri.hashCode()}" else ""
        val effectiveChannelId = channelId + soundSuffix
        val channel = NotificationChannel(
            effectiveChannelId,
            "$appLabel Notifications",
            if (isSilent) NotificationManager.IMPORTANCE_LOW else importance
        ).apply {
            description = "Mirrored notifications from $appLabel"
            enableVibration(true)
            this.vibrationPattern = vibrationPattern
            this.group = groupId
            if (customSoundUri.isNotEmpty()) {
                setSound(
                    Uri.parse(customSoundUri),
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

        val builder = NotificationCompat.Builder(context, effectiveChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$appLabel: $title")
            .setContentText(text)
            .setPriority(if (isSilent) NotificationCompat.PRIORITY_LOW else compatPriority)
            .setAutoCancel(autoCancel)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(groupId)

        if (iconBitmap != null) {
            builder.setLargeIcon(iconBitmap)
        }

        if (subText.isNotEmpty()) {
            builder.setSubText(subText)
        } else {
            builder.setSubText(appLabel)
        }

        if (text.length > bigTextThreshold) {
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
