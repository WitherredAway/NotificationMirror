package com.notifmirror.wear

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest

class NotificationComplicationService : ComplicationDataSourceService() {

    companion object {
        private const val TAG = "NotifMirrorComplication"
        private const val PREFS_NAME = "notif_complication"
        private const val KEY_LAST_TITLE = "last_title"
        private const val KEY_LAST_TEXT = "last_text"
        private const val KEY_LAST_APP = "last_app"
        private const val KEY_LAST_PACKAGE = "last_package"
        private const val KEY_LAST_TIME = "last_time"

        fun updateComplication(context: Context, appLabel: String, packageName: String, title: String, text: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_LAST_TITLE, title)
                .putString(KEY_LAST_TEXT, text)
                .putString(KEY_LAST_APP, appLabel)
                .putString(KEY_LAST_PACKAGE, packageName)
                .putLong(KEY_LAST_TIME, System.currentTimeMillis())
                .apply()

            // Also store per-app latest
            prefs.edit()
                .putString("app_title_$packageName", title)
                .putString("app_text_$packageName", text)
                .putString("app_label_$packageName", appLabel)
                .putLong("app_time_$packageName", System.currentTimeMillis())
                .apply()

            // Request complication update
            try {
                val component = ComponentName(context, NotificationComplicationService::class.java)
                androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
                    .create(context, component)
                    .requestUpdateAll()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to request complication update", e)
            }
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
    }

    data class ComplicationInfo(
        val appLabel: String,
        val title: String,
        val text: String,
        val time: Long,
        val packageName: String = ""
    )

    /**
     * Try to get the app icon for the given package as a MonochromaticImage.
     * Falls back to null if the package is not found.
     */
    private fun getAppIcon(packageName: String): MonochromaticImage? {
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
            Log.w(TAG, "Failed to get app icon for complication: $packageName", e)
            null
        }
    }

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        val info = getLatestNotification(this)

        val monoIcon = MonochromaticImage.Builder(
            image = Icon.createWithResource(this, R.drawable.ic_notification)
        ).build()

        // Try to get app-specific icon
        val appIcon = getAppIcon(info.packageName)

        val data = when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> {
                // Show notification content only (not app name)
                val displayText = if (info.text.isNotEmpty()) info.text
                    else if (info.title.isNotEmpty()) info.title
                    else "No notifs"
                val truncated = if (displayText.length > 20) displayText.take(17) + "..." else displayText
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(truncated).build(),
                    contentDescription = PlainComplicationText.Builder(
                        "${info.appLabel}: ${info.title} - ${info.text}"
                    ).build()
                )
                    .setMonochromaticImage(appIcon ?: monoIcon)
                    .build()
            }
            ComplicationType.LONG_TEXT -> {
                if (info.title.isEmpty()) {
                    LongTextComplicationData.Builder(
                        text = PlainComplicationText.Builder("No notifications").build(),
                        contentDescription = PlainComplicationText.Builder("No notifications received").build()
                    )
                        .setMonochromaticImage(monoIcon)
                        .build()
                } else {
                    // Show notification content only
                    val contentText = info.text.ifEmpty { info.title }
                    val truncated = if (contentText.length > 30) contentText.take(27) + "..." else contentText
                    LongTextComplicationData.Builder(
                        text = PlainComplicationText.Builder(truncated).build(),
                        contentDescription = PlainComplicationText.Builder(
                            "${info.appLabel}: ${info.title} - ${info.text}"
                        ).build()
                    )
                        .setMonochromaticImage(appIcon ?: monoIcon)
                        .setTitle(PlainComplicationText.Builder(info.appLabel).build())
                        .build()
                }
            }
            else -> null
        }

        if (data != null) {
            listener.onComplicationData(data)
        } else {
            listener.onComplicationData(null)
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                val previewIcon = MonochromaticImage.Builder(
                    image = Icon.createWithResource(this, R.drawable.ic_notification)
                ).build()
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder("WhatsApp").build(),
                    contentDescription = PlainComplicationText.Builder("Latest notification").build()
                )
                    .setMonochromaticImage(previewIcon)
                    .build()
            }
            ComplicationType.LONG_TEXT -> {
                val previewIcon = MonochromaticImage.Builder(
                    image = Icon.createWithResource(this, R.drawable.ic_notification)
                ).build()
                LongTextComplicationData.Builder(
                    text = PlainComplicationText.Builder("Hey, how are you?").build(),
                    contentDescription = PlainComplicationText.Builder("Latest notification content").build()
                )
                    .setMonochromaticImage(previewIcon)
                    .setTitle(PlainComplicationText.Builder("WhatsApp").build())
                    .build()
            }
            else -> null
        }
    }
}
