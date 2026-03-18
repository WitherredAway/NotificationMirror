package com.notifmirror.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream

/**
 * Shared utility methods used by multiple components (MainActivity,
 * NotificationListener, ReplyReceiverService) to avoid code duplication.
 */
object WearSyncHelper {

    private const val TAG = "WearSyncHelper"

    // LRU cache for app icon Base64 strings to avoid re-encoding on every notification
    private val iconCache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > 50
        }
    }
    private val iconCacheLock = Any()

    /**
     * Sync the mirroring enabled/disabled state to the watch via DataClient.
     * Must be called from a coroutine (suspend function).
     */
    suspend fun syncMirroringToWatch(context: Context, enabled: Boolean) {
        try {
            val putReq = PutDataMapRequest.create("/mirroring_state").apply {
                dataMap.putBoolean("enabled", enabled)
                dataMap.putLong("timestamp", System.currentTimeMillis())
            }
            Wearable.getDataClient(context)
                .putDataItem(putReq.asPutDataRequest().setUrgent())
                .await()
            Log.d(TAG, "Mirroring state synced to watch: enabled=$enabled")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync mirroring state to watch", e)
        }
    }

    /**
     * Convert an app's icon to a Base64-encoded PNG string for sending to the watch.
     * Returns null if the icon cannot be loaded.
     */
    fun getAppIconBase64(context: Context, packageName: String): String? {
        // Check cache first
        synchronized(iconCacheLock) {
            iconCache[packageName]?.let { return it }
        }
        return try {
            val iconSize = 48
            val iconQuality = 80
            val drawable = context.packageManager.getApplicationIcon(packageName)
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
            // Recycle bitmap to free native memory. For BitmapDrawable, createScaledBitmap
            // returns a NEW bitmap (since dimensions differ), so it's safe to recycle.
            bitmap.recycle()
            val encoded = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            synchronized(iconCacheLock) {
                iconCache[packageName] = encoded
            }
            encoded
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get icon for $packageName", e)
            null
        }
    }
}
