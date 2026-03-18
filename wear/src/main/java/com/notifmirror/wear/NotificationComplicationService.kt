package com.notifmirror.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * Complication data source showing the latest mirrored notification on the watch face.
 *
 * Supported types:
 *  SHORT_TEXT         — app icon + notification text, app name as title
 *  LONG_TEXT          — app icon + full text, app name as title
 *  RANGED_VALUE       — notification count gauge (0..50) with icon + count
 *  MONOCHROMATIC_IMAGE — notification bell icon (for icon-only slots)
 *  SMALL_IMAGE        — colored app icon (for image slots)
 *
 * Tapping any complication opens the notification log.
 */
class NotificationComplicationService : SuspendingComplicationDataSourceService() {

    companion object {
        private const val TAG = "NotifMirrorComplication"
        private const val PREFS_NAME = "notif_complication"
        private const val KEY_LAST_TITLE = "last_title"
        private const val KEY_LAST_TEXT = "last_text"
        private const val KEY_LAST_APP = "last_app"
        private const val KEY_LAST_PACKAGE = "last_package"
        private const val KEY_LAST_TIME = "last_time"
        private const val KEY_ACTIVE_COUNT = "active_count"

        /** Upper bound for the RANGED_VALUE gauge. */
        private const val MAX_NOTIF_COUNT = 50f

        fun updateComplication(context: Context, appLabel: String, packageName: String, title: String, text: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            // Single batch write instead of two separate apply() calls
            prefs.edit()
                .putString(KEY_LAST_TITLE, title)
                .putString(KEY_LAST_TEXT, text)
                .putString(KEY_LAST_APP, appLabel)
                .putString(KEY_LAST_PACKAGE, packageName)
                .putLong(KEY_LAST_TIME, now)
                .putString("app_title_$packageName", title)
                .putString("app_text_$packageName", text)
                .putString("app_label_$packageName", appLabel)
                .putLong("app_time_$packageName", now)
                .apply()

            requestUpdate(context)
        }

        /** Increment the active-notification counter and refresh all complications. */
        fun incrementActiveCount(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = prefs.getInt(KEY_ACTIVE_COUNT, 0)
            prefs.edit().putInt(KEY_ACTIVE_COUNT, current + 1).apply()
            requestUpdate(context)
        }

        /** Decrement the active-notification counter and refresh all complications. */
        fun decrementActiveCount(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = prefs.getInt(KEY_ACTIVE_COUNT, 0)
            if (current > 0) {
                prefs.edit().putInt(KEY_ACTIVE_COUNT, current - 1).apply()
                requestUpdate(context)
            }
        }

        fun getActiveCount(context: Context): Int {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_ACTIVE_COUNT, 0)
        }

        fun getLatestNotification(context: Context): ComplicationInfo {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // Check complication source setting (synced from phone via notification JSON)
            val settingsPrefs = context.getSharedPreferences("notif_mirror_settings", Context.MODE_PRIVATE)
            val source = settingsPrefs.getString("complication_source", "most_recent") ?: "most_recent"
            val filterApp = settingsPrefs.getString("complication_app", "") ?: ""

            if (source == "specific_app" && filterApp.isNotEmpty()) {
                val title = prefs.getString("app_title_$filterApp", "") ?: ""
                val text = prefs.getString("app_text_$filterApp", "") ?: ""
                val appLabel = prefs.getString("app_label_$filterApp", "") ?: ""
                val time = prefs.getLong("app_time_$filterApp", 0)
                return ComplicationInfo(appLabel, title, text, time, filterApp)
            }

            val title = prefs.getString(KEY_LAST_TITLE, "") ?: ""
            val text = prefs.getString(KEY_LAST_TEXT, "") ?: ""
            val appLabel = prefs.getString(KEY_LAST_APP, "") ?: ""
            val time = prefs.getLong(KEY_LAST_TIME, 0)
            val pkg = prefs.getString(KEY_LAST_PACKAGE, "") ?: ""
            return ComplicationInfo(appLabel, title, text, time, pkg)
        }

        private fun requestUpdate(context: Context) {
            try {
                val component = ComponentName(context, NotificationComplicationService::class.java)
                ComplicationDataSourceUpdateRequester
                    .create(context, component)
                    .requestUpdateAll()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to request complication update", e)
            }
        }
    }

    data class ComplicationInfo(
        val appLabel: String,
        val title: String,
        val text: String,
        val time: Long,
        val packageName: String = ""
    )

    // ── Icon helpers ──────────────────────────────────────────────────

    private fun defaultMonoIcon(): MonochromaticImage =
        MonochromaticImage.Builder(
            image = Icon.createWithResource(this, R.drawable.ic_notification)
        ).build()

    /** Colored app icon for SMALL_IMAGE slots. */
    private fun getAppSmallImage(packageName: String): SmallImage? {
        if (packageName.isEmpty()) return null
        return try {
            val drawable = packageManager.getApplicationIcon(packageName)
            val bitmap = if (drawable is BitmapDrawable) {
                Bitmap.createScaledBitmap(drawable.bitmap, 64, 64, true)
            } else {
                val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, 64, 64)
                drawable.draw(canvas)
                bmp
            }
            SmallImage.Builder(
                image = Icon.createWithBitmap(bitmap),
                type = SmallImageType.ICON
            ).build()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get app small image: $packageName", e)
            null
        }
    }

    /** Monochromatic version of the app icon for mono-only slots. */
    private fun getAppMonoIcon(packageName: String): MonochromaticImage? {
        if (packageName.isEmpty()) return null
        return try {
            val drawable = packageManager.getApplicationIcon(packageName)
            val bitmap = if (drawable is BitmapDrawable) {
                Bitmap.createScaledBitmap(drawable.bitmap, 48, 48, true)
            } else {
                val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, 48, 48)
                drawable.draw(canvas)
                bmp
            }
            MonochromaticImage.Builder(
                image = Icon.createWithBitmap(bitmap)
            ).build()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get app mono icon: $packageName", e)
            null
        }
    }

    // ── Tap action ────────────────────────────────────────────────────

    private fun tapAction(): PendingIntent {
        val intent = Intent(this, LogActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ── Complication request ──────────────────────────────────────────

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val info = getLatestNotification(this)
        val count = getActiveCount(this)
        val tap = tapAction()
        val monoIcon = defaultMonoIcon()
        val appMono = getAppMonoIcon(info.packageName)
        val appSmall = getAppSmallImage(info.packageName)

        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> {
                val displayText = when {
                    info.text.isNotEmpty() -> info.text
                    info.title.isNotEmpty() -> info.title
                    else -> "No notifs"
                }
                val truncated = if (displayText.length > 20) displayText.take(17) + "..." else displayText
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(truncated).build(),
                    contentDescription = PlainComplicationText.Builder(
                        "${info.appLabel}: ${info.title} - ${info.text}"
                    ).build()
                )
                    .setMonochromaticImage(appMono ?: monoIcon)
                    .setTitle(
                        if (info.appLabel.isNotEmpty())
                            PlainComplicationText.Builder(info.appLabel).build()
                        else null
                    )
                    .setTapAction(tap)
                    .build()
            }

            ComplicationType.LONG_TEXT -> {
                if (info.title.isEmpty() && info.text.isEmpty()) {
                    LongTextComplicationData.Builder(
                        text = PlainComplicationText.Builder("No notifications").build(),
                        contentDescription = PlainComplicationText.Builder("No notifications received").build()
                    )
                        .setMonochromaticImage(monoIcon)
                        .setTapAction(tap)
                        .build()
                } else {
                    val contentText = info.text.ifEmpty { info.title }
                    LongTextComplicationData.Builder(
                        text = PlainComplicationText.Builder(contentText).build(),
                        contentDescription = PlainComplicationText.Builder(
                            "${info.appLabel}: ${info.title} - ${info.text}"
                        ).build()
                    )
                        .setMonochromaticImage(appMono ?: monoIcon)
                        .setSmallImage(appSmall)
                        .setTitle(PlainComplicationText.Builder(info.appLabel).build())
                        .setTapAction(tap)
                        .build()
                }
            }

            ComplicationType.RANGED_VALUE -> {
                val value = count.toFloat().coerceIn(0f, MAX_NOTIF_COUNT)
                val countLabel = if (count > 0) "$count" else "0"
                RangedValueComplicationData.Builder(
                    value = value,
                    min = 0f,
                    max = MAX_NOTIF_COUNT,
                    contentDescription = PlainComplicationText.Builder(
                        "$count active notifications"
                    ).build()
                )
                    .setText(PlainComplicationText.Builder(countLabel).build())
                    .setTitle(PlainComplicationText.Builder("Notifs").build())
                    .setMonochromaticImage(monoIcon)
                    .setTapAction(tap)
                    .build()
            }

            ComplicationType.MONOCHROMATIC_IMAGE -> {
                MonochromaticImageComplicationData.Builder(
                    monochromaticImage = appMono ?: monoIcon,
                    contentDescription = PlainComplicationText.Builder(
                        if (info.appLabel.isNotEmpty()) "Latest: ${info.appLabel}" else "Notification Mirror"
                    ).build()
                )
                    .setTapAction(tap)
                    .build()
            }

            ComplicationType.SMALL_IMAGE -> {
                val image = appSmall ?: SmallImage.Builder(
                    image = Icon.createWithResource(this, R.drawable.ic_notification),
                    type = SmallImageType.ICON
                ).build()
                SmallImageComplicationData.Builder(
                    smallImage = image,
                    contentDescription = PlainComplicationText.Builder(
                        if (info.appLabel.isNotEmpty()) "Latest: ${info.appLabel}" else "Notification Mirror"
                    ).build()
                )
                    .setTapAction(tap)
                    .build()
            }

            else -> null
        }
    }

    // ── Preview data ──────────────────────────────────────────────────

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val previewMono = defaultMonoIcon()
        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder("Hey!").build(),
                    contentDescription = PlainComplicationText.Builder("Latest notification preview").build()
                )
                    .setMonochromaticImage(previewMono)
                    .setTitle(PlainComplicationText.Builder("WhatsApp").build())
                    .build()
            }

            ComplicationType.LONG_TEXT -> {
                LongTextComplicationData.Builder(
                    text = PlainComplicationText.Builder("Hey, are you coming tonight?").build(),
                    contentDescription = PlainComplicationText.Builder("Latest notification preview").build()
                )
                    .setMonochromaticImage(previewMono)
                    .setTitle(PlainComplicationText.Builder("WhatsApp").build())
                    .build()
            }

            ComplicationType.RANGED_VALUE -> {
                RangedValueComplicationData.Builder(
                    value = 7f,
                    min = 0f,
                    max = MAX_NOTIF_COUNT,
                    contentDescription = PlainComplicationText.Builder("7 notifications").build()
                )
                    .setText(PlainComplicationText.Builder("7").build())
                    .setTitle(PlainComplicationText.Builder("Notifs").build())
                    .setMonochromaticImage(previewMono)
                    .build()
            }

            ComplicationType.MONOCHROMATIC_IMAGE -> {
                MonochromaticImageComplicationData.Builder(
                    monochromaticImage = previewMono,
                    contentDescription = PlainComplicationText.Builder("Notification Mirror").build()
                ).build()
            }

            ComplicationType.SMALL_IMAGE -> {
                val image = SmallImage.Builder(
                    image = Icon.createWithResource(this, R.drawable.ic_notification),
                    type = SmallImageType.ICON
                ).build()
                SmallImageComplicationData.Builder(
                    smallImage = image,
                    contentDescription = PlainComplicationText.Builder("Notification Mirror").build()
                ).build()
            }

            else -> null
        }
    }
}
