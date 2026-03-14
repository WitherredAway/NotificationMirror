package com.notifmirror.wear

import android.content.Context
import android.content.SharedPreferences

class MuteManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "notif_mirror_mutes"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun muteApp(packageName: String, durationMinutes: Int) {
        val unmuteTime = System.currentTimeMillis() + (durationMinutes * 60 * 1000L)
        prefs.edit().putLong("mute_$packageName", unmuteTime).apply()
    }

    fun isAppMuted(packageName: String): Boolean {
        val unmuteTime = prefs.getLong("mute_$packageName", 0)
        if (unmuteTime == 0L) return false
        if (System.currentTimeMillis() >= unmuteTime) {
            // Mute expired, clean up
            prefs.edit().remove("mute_$packageName").apply()
            return false
        }
        return true
    }

    fun unmuteApp(packageName: String) {
        prefs.edit().remove("mute_$packageName").apply()
    }

    fun getMutedApps(): Map<String, Long> {
        val now = System.currentTimeMillis()
        val muted = mutableMapOf<String, Long>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("mute_") && value is Long && value > now) {
                muted[key.removePrefix("mute_")] = value
            }
        }
        return muted
    }
}
