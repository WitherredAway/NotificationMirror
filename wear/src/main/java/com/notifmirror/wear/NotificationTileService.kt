package com.notifmirror.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.wear.tiles.ActionBuilders
import androidx.wear.tiles.ColorBuilders
import androidx.wear.tiles.DimensionBuilders
import androidx.wear.tiles.LayoutElementBuilders
import androidx.wear.tiles.ModifiersBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.tiles.TimelineBuilders
import android.util.TypedValue
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class NotificationTileService : TileService() {

    companion object {
        private const val PREFS_NAME = "notif_tile_counts"
        private const val RESOURCES_VERSION = "1"

        fun incrementCount(context: Context, packageName: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = prefs.getInt(packageName, 0)
            prefs.edit().putInt(packageName, current + 1).apply()
        }

        fun decrementCount(context: Context, packageName: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = prefs.getInt(packageName, 0)
            if (current > 0) {
                prefs.edit().putInt(packageName, current - 1).apply()
            }
        }

        fun getCounts(context: Context): Map<String, Int> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val result = mutableMapOf<String, Int>()
            for ((key, value) in prefs.all) {
                if (value is Int && value > 0) {
                    result[key] = value
                }
            }
            return result
        }

        fun clearCounts(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val counts = getCounts(this)
        val total = counts.values.sum()

        val layout = buildLayout(counts, total)

        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(
                        LayoutElementBuilders.Layout.Builder()
                            .setRoot(layout)
                            .build()
                    )
                    .build()
            )
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTimeline(timeline)
            .setFreshnessIntervalMillis(60000)
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build()
        )
    }

    private fun resolveThemeColor(attrResId: Int, fallback: Int): Int {
        val typedValue = TypedValue()
        return if (theme.resolveAttribute(attrResId, typedValue, true)) {
            typedValue.data
        } else {
            fallback
        }
    }

    private fun buildLayout(counts: Map<String, Int>, total: Int): LayoutElementBuilders.LayoutElement {
        val colorPrimary = resolveThemeColor(com.google.android.material.R.attr.colorPrimary, 0xFFD0BCFF.toInt())
        val colorOnSurface = resolveThemeColor(com.google.android.material.R.attr.colorOnSurface, 0xFFE6E1E5.toInt())
        val colorOnSurfaceVariant = resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFCAC4D0.toInt())
        val columnBuilder = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        // Title
        columnBuilder.addContent(
            LayoutElementBuilders.Text.Builder()
                .setText("Notifications")
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder()
                        .setSize(DimensionBuilders.sp(14f))
                        .setColor(ColorBuilders.argb(colorPrimary))
                        .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                        .build()
                )
                .build()
        )

        // Spacer
        columnBuilder.addContent(
            LayoutElementBuilders.Spacer.Builder()
                .setHeight(DimensionBuilders.dp(4f))
                .build()
        )

        // Total count
        columnBuilder.addContent(
            LayoutElementBuilders.Text.Builder()
                .setText("$total total")
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder()
                        .setSize(DimensionBuilders.sp(12f))
                        .setColor(ColorBuilders.argb(colorOnSurface))
                        .build()
                )
                .build()
        )

        // Spacer
        columnBuilder.addContent(
            LayoutElementBuilders.Spacer.Builder()
                .setHeight(DimensionBuilders.dp(6f))
                .build()
        )

        // Top apps by count (max 4)
        val sorted = counts.entries.sortedByDescending { it.value }.take(4)
        for (entry in sorted) {
            val appLabel = getShortAppLabel(entry.key)
            columnBuilder.addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("$appLabel: ${entry.value}")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(DimensionBuilders.sp(11f))
                            .setColor(ColorBuilders.argb(colorOnSurfaceVariant))
                            .build()
                    )
                    .build()
            )
        }

        if (counts.isEmpty()) {
            columnBuilder.addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("No notifications")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(DimensionBuilders.sp(11f))
                            .setColor(ColorBuilders.argb(colorOnSurfaceVariant))
                            .build()
                    )
                    .build()
            )
        }

        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(columnBuilder.build())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setOnClick(
                                ActionBuilders.LaunchAction.Builder()
                                    .setAndroidActivity(
                                        ActionBuilders.AndroidActivity.Builder()
                                            .setPackageName("com.notifmirror.wear")
                                            .setClassName("com.notifmirror.wear.MainActivity")
                                            .build()
                                    )
                                    .build()
                            )
                            .setId("open_app")
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun getShortAppLabel(packageName: String): String {
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
            else -> packageName.split(".").lastOrNull()?.replaceFirstChar { it.uppercase() } ?: packageName
        }
    }
}
