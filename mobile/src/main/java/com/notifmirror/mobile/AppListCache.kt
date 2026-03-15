package com.notifmirror.mobile

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.json.JSONObject

/**
 * Caches installed app labels in SharedPreferences for instant loading.
 * Icons are loaded lazily per-item by the adapter (they can't be cached in prefs).
 * The cache is refreshed in the background on every load.
 */
object AppListCache {

    private const val PREFS_NAME = "app_list_cache"
    private const val KEY_APP_DATA = "cached_apps"
    private const val KEY_CACHE_TIME = "cache_time"

    data class CachedApp(
        val packageName: String,
        val label: String
    )

    /**
     * Get cached app list instantly (may be empty on first run).
     */
    fun getCachedApps(context: Context): List<CachedApp> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_APP_DATA, null) ?: return emptyList()
        return try {
            val json = JSONObject(raw)
            json.keys().asSequence().map { pkg ->
                CachedApp(pkg, json.getString(pkg))
            }.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Refresh the cache from PackageManager. Call from a background thread.
     * Returns the fresh list.
     */
    fun refreshCache(context: Context): List<CachedApp> {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || pm.getLaunchIntentForPackage(it.packageName) != null }
            .filter { it.packageName != context.packageName }

        val result = apps.map { appInfo ->
            CachedApp(
                packageName = appInfo.packageName,
                label = pm.getApplicationLabel(appInfo).toString()
            )
        }

        // Save to prefs
        val json = JSONObject()
        for (app in result) {
            json.put(app.packageName, app.label)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APP_DATA, json.toString())
            .putLong(KEY_CACHE_TIME, System.currentTimeMillis())
            .apply()

        return result
    }

    /**
     * Convert cached apps to AppInfo list with icons loaded from PackageManager.
     * Call from a background thread.
     */
    fun toAppInfoList(context: Context, cachedApps: List<CachedApp>): List<AppPickerActivity.AppInfo> {
        val pm = context.packageManager
        return cachedApps.map { cached ->
            AppPickerActivity.AppInfo(
                packageName = cached.packageName,
                label = cached.label,
                icon = try { pm.getApplicationIcon(cached.packageName) } catch (_: Exception) { null }
            )
        }
    }
}
