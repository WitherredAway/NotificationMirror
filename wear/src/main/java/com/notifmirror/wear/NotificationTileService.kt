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
        private const val RESOURCES_VERSION = "2"

        private const val RES_ICON_PAUSE = "ic_pause"
        private const val RES_ICON_PLAY = "ic_play"
        private const val RES_ICON_MUTE = "ic_mute"
        private const val RES_ICON_UNMUTE = "ic_unmute"
        private const val RES_ICON_INFO = "ic_info"

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
                .addIdToImageMapping(
                    RES_ICON_PAUSE,
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_pause)
                                .build()
                        )
                        .build()
                )
                .addIdToImageMapping(
                    RES_ICON_PLAY,
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_play)
                                .build()
                        )
                        .build()
                )
                .addIdToImageMapping(
                    RES_ICON_MUTE,
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_mute)
                                .build()
                        )
                        .build()
                )
                .addIdToImageMapping(
                    RES_ICON_UNMUTE,
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_unmute)
                                .build()
                        )
                        .build()
                )
                .addIdToImageMapping(
                    RES_ICON_INFO,
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_info)
                                .build()
                        )
                        .build()
                )
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
        val colorSurfaceVariant = resolveThemeColor(com.google.android.material.R.attr.colorSurfaceVariant, 0xFF49454F.toInt())

        val prefs = getSharedPreferences("notif_mirror_settings", MODE_PRIVATE)
        val mirroringEnabled = prefs.getBoolean("mirroring_enabled", true)
        val muteManager = MuteManager(this)
        val allMuted = muteManager.isAllMuted()

        val columnBuilder = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        // Title
        columnBuilder.addContent(
            LayoutElementBuilders.Text.Builder()
                .setText("Notification Mirror")
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder()
                        .setSize(DimensionBuilders.sp(13f))
                        .setColor(ColorBuilders.argb(colorPrimary))
                        .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                        .build()
                )
                .build()
        )

        // Spacer
        columnBuilder.addContent(
            LayoutElementBuilders.Spacer.Builder()
                .setHeight(DimensionBuilders.dp(3f))
                .build()
        )

        // Status line
        val statusText = when {
            !mirroringEnabled -> "Paused"
            allMuted -> "Muted"
            total > 0 -> "$total notifications"
            else -> "No notifications"
        }
        columnBuilder.addContent(
            LayoutElementBuilders.Text.Builder()
                .setText(statusText)
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder()
                        .setSize(DimensionBuilders.sp(11f))
                        .setColor(ColorBuilders.argb(colorOnSurface))
                        .build()
                )
                .build()
        )

        // Spacer
        columnBuilder.addContent(
            LayoutElementBuilders.Spacer.Builder()
                .setHeight(DimensionBuilders.dp(3f))
                .build()
        )

        // Top apps by count (max 3)
        val sorted = counts.entries.sortedByDescending { it.value }.take(3)
        for (entry in sorted) {
            val appLabel = getShortAppLabel(entry.key)
            columnBuilder.addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("$appLabel: ${entry.value}")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(DimensionBuilders.sp(10f))
                            .setColor(ColorBuilders.argb(colorOnSurfaceVariant))
                            .build()
                    )
                    .build()
            )
        }

        // Spacer before buttons
        columnBuilder.addContent(
            LayoutElementBuilders.Spacer.Builder()
                .setHeight(DimensionBuilders.dp(6f))
                .build()
        )

        // Action buttons row
        val buttonRow = LayoutElementBuilders.Row.Builder()
            .setWidth(DimensionBuilders.wrap())
            .setHeight(DimensionBuilders.wrap())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)

        // Toggle Mirroring button
        val mirrorLabel = if (mirroringEnabled) "Pause" else "Resume"
        val mirrorIcon = if (mirroringEnabled) RES_ICON_PAUSE else RES_ICON_PLAY
        buttonRow.addContent(buildActionButton(mirrorLabel, mirrorIcon, TileActionActivity.ACTION_TOGGLE_MIRRORING, colorPrimary, colorSurfaceVariant))

        // Spacer between buttons
        buttonRow.addContent(
            LayoutElementBuilders.Spacer.Builder()
                .setWidth(DimensionBuilders.dp(6f))
                .build()
        )

        // Mute All button
        val muteLabel = if (allMuted) "Unmute" else "Mute"
        val muteIcon = if (allMuted) RES_ICON_UNMUTE else RES_ICON_MUTE
        buttonRow.addContent(buildActionButton(muteLabel, muteIcon, TileActionActivity.ACTION_MUTE_ALL, colorPrimary, colorSurfaceVariant))

        // Spacer between buttons
        buttonRow.addContent(
            LayoutElementBuilders.Spacer.Builder()
                .setWidth(DimensionBuilders.dp(6f))
                .build()
        )

        // Connection Details button
        buttonRow.addContent(buildActionButton("Info", RES_ICON_INFO, TileActionActivity.ACTION_CONNECTION_DETAILS, colorPrimary, colorSurfaceVariant))

        columnBuilder.addContent(buttonRow.build())

        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(columnBuilder.build())
            .build()
    }

    private fun buildActionButton(
        label: String,
        iconResId: String,
        action: String,
        accentColor: Int,
        bgColor: Int
    ): LayoutElementBuilders.LayoutElement {
        val buttonContent = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.wrap())
            .setHeight(DimensionBuilders.wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Image.Builder()
                    .setResourceId(iconResId)
                    .setWidth(DimensionBuilders.dp(18f))
                    .setHeight(DimensionBuilders.dp(18f))
                    .setColorFilter(
                        LayoutElementBuilders.ColorFilter.Builder()
                            .setTint(ColorBuilders.argb(accentColor))
                            .build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Spacer.Builder()
                    .setHeight(DimensionBuilders.dp(2f))
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(label)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(DimensionBuilders.sp(9f))
                            .setColor(ColorBuilders.argb(accentColor))
                            .build()
                    )
                    .build()
            )
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.dp(48f))
            .setHeight(DimensionBuilders.dp(48f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(buttonContent)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setOnClick(
                                ActionBuilders.LaunchAction.Builder()
                                    .setAndroidActivity(
                                        ActionBuilders.AndroidActivity.Builder()
                                            .setPackageName("com.notifmirror.wear")
                                            .setClassName("com.notifmirror.wear.TileActionActivity")
                                            .addKeyToExtraMapping(
                                                TileActionActivity.EXTRA_ACTION,
                                                ActionBuilders.AndroidStringExtra.Builder()
                                                    .setValue(action)
                                                    .build()
                                            )
                                            .build()
                                    )
                                    .build()
                            )
                            .setId("action_" + action)
                            .build()
                    )
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(bgColor))
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(DimensionBuilders.dp(14f))
                                    .build()
                            )
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
