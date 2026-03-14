package com.notifmirror.mobile

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "notif_mirror_prefs"
        private const val KEY_WHITELISTED_APPS = "whitelisted_apps"
        private const val KEY_KEYWORD_WHITELIST = "keyword_whitelist"
        private const val KEY_KEYWORD_BLACKLIST = "keyword_blacklist"
        private const val KEY_DND_SYNC = "dnd_sync_enabled"
        private const val KEY_SCREEN_OFF_MODE = "screen_off_mode"
        private const val KEY_MUTE_DURATION = "mute_duration_minutes"
        private const val KEY_VIBRATION_PREFIX = "vibration_"
        private const val KEY_SOUND_PREFIX = "sound_"
        private const val KEY_SOUND_NAME_PREFIX = "sound_name_"
        private const val KEY_DEFAULT_VIBRATION = "default_vibration_pattern"
        private const val KEY_MIRROR_ONGOING = "mirror_ongoing"
        private const val KEY_NOTIF_PRIORITY = "notification_priority"
        private const val KEY_BIG_TEXT_THRESHOLD = "big_text_threshold"
        private const val KEY_AUTO_CANCEL = "auto_cancel"
        private const val KEY_AUTO_DISMISS_SYNC = "auto_dismiss_sync"
        private const val KEY_SHOW_OPEN_BUTTON = "show_open_on_watch"
        private const val KEY_SHOW_MUTE_BUTTON = "show_mute_button"

        // Screen off modes
        const val SCREEN_MODE_ALWAYS = 0
        const val SCREEN_MODE_SCREEN_OFF_ONLY = 1
        const val SCREEN_MODE_SILENT_WHEN_ON = 2

        // Notification priority values
        const val PRIORITY_HIGH = 1
        const val PRIORITY_DEFAULT = 0
        const val PRIORITY_LOW = -1
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- App Whitelist ---

    fun getWhitelistedApps(): Set<String> {
        return prefs.getStringSet(KEY_WHITELISTED_APPS, emptySet()) ?: emptySet()
    }

    fun setWhitelistedApps(apps: Set<String>) {
        prefs.edit().putStringSet(KEY_WHITELISTED_APPS, apps).apply()
    }

    fun isAppWhitelisted(packageName: String): Boolean {
        val whitelist = getWhitelistedApps()
        if (whitelist.isEmpty()) return true
        return whitelist.contains(packageName)
    }

    // --- Keyword Whitelist (regex patterns) ---

    fun getKeywordWhitelist(): List<String> {
        val raw = prefs.getString(KEY_KEYWORD_WHITELIST, "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split("\n").filter { it.isNotBlank() }
    }

    fun setKeywordWhitelist(patterns: List<String>) {
        prefs.edit().putString(KEY_KEYWORD_WHITELIST, patterns.joinToString("\n")).apply()
    }

    // --- Keyword Blacklist (regex patterns) ---

    fun getKeywordBlacklist(): List<String> {
        val raw = prefs.getString(KEY_KEYWORD_BLACKLIST, "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split("\n").filter { it.isNotBlank() }
    }

    fun setKeywordBlacklist(patterns: List<String>) {
        prefs.edit().putString(KEY_KEYWORD_BLACKLIST, patterns.joinToString("\n")).apply()
    }

    // --- DND Sync ---

    fun isDndSyncEnabled(): Boolean = prefs.getBoolean(KEY_DND_SYNC, true)

    fun setDndSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DND_SYNC, enabled).apply()
    }

    // --- Screen Off Mode ---

    fun getScreenOffMode(): Int = prefs.getInt(KEY_SCREEN_OFF_MODE, SCREEN_MODE_ALWAYS)

    fun setScreenOffMode(mode: Int) {
        prefs.edit().putInt(KEY_SCREEN_OFF_MODE, mode).apply()
    }

    // --- Mute Duration ---

    fun getMuteDurationMinutes(): Int = prefs.getInt(KEY_MUTE_DURATION, 30)

    fun setMuteDurationMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_MUTE_DURATION, minutes).apply()
    }

    // --- Mirror Ongoing Notifications ---

    fun isMirrorOngoingEnabled(): Boolean = prefs.getBoolean(KEY_MIRROR_ONGOING, false)

    fun setMirrorOngoingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MIRROR_ONGOING, enabled).apply()
    }

    // --- Notification Priority ---

    fun getNotificationPriority(): Int = prefs.getInt(KEY_NOTIF_PRIORITY, PRIORITY_HIGH)

    fun setNotificationPriority(priority: Int) {
        prefs.edit().putInt(KEY_NOTIF_PRIORITY, priority).apply()
    }

    // --- BigText Threshold ---

    fun getBigTextThreshold(): Int = prefs.getInt(KEY_BIG_TEXT_THRESHOLD, 40)

    fun setBigTextThreshold(threshold: Int) {
        prefs.edit().putInt(KEY_BIG_TEXT_THRESHOLD, threshold).apply()
    }

    // --- Auto Cancel ---

    fun isAutoCancelEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_CANCEL, true)

    fun setAutoCancelEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CANCEL, enabled).apply()
    }

    // --- Auto Dismiss Sync ---

    fun isAutoDismissSyncEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_DISMISS_SYNC, true)

    fun setAutoDismissSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DISMISS_SYNC, enabled).apply()
    }

    // --- Show Open on Watch Button ---

    fun isShowOpenButtonEnabled(): Boolean = prefs.getBoolean(KEY_SHOW_OPEN_BUTTON, true)

    fun setShowOpenButtonEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_OPEN_BUTTON, enabled).apply()
    }

    // --- Show Mute Button ---

    fun isShowMuteButtonEnabled(): Boolean = prefs.getBoolean(KEY_SHOW_MUTE_BUTTON, true)

    fun setShowMuteButtonEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_MUTE_BUTTON, enabled).apply()
    }

    // --- Default Vibration Pattern ---

    fun getDefaultVibrationPattern(): String {
        return prefs.getString(KEY_DEFAULT_VIBRATION, "0,200,100,200") ?: "0,200,100,200"
    }

    fun setDefaultVibrationPattern(pattern: String) {
        prefs.edit().putString(KEY_DEFAULT_VIBRATION, pattern).apply()
    }

    // --- Per-App Vibration ---

    fun getVibrationPattern(packageName: String): String {
        return prefs.getString(KEY_VIBRATION_PREFIX + packageName, "") ?: ""
    }

    fun setVibrationPattern(packageName: String, pattern: String) {
        prefs.edit().putString(KEY_VIBRATION_PREFIX + packageName, pattern).apply()
    }

    fun getAllCustomVibrations(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(KEY_VIBRATION_PREFIX) && value is String && value.isNotEmpty()) {
                val pkg = key.removePrefix(KEY_VIBRATION_PREFIX)
                result[pkg] = value
            }
        }
        return result
    }

    fun removeVibrationPattern(packageName: String) {
        prefs.edit().remove(KEY_VIBRATION_PREFIX + packageName).apply()
    }

    // --- Per-App Sound ---

    fun getSoundUri(packageName: String): String {
        return prefs.getString(KEY_SOUND_PREFIX + packageName, "") ?: ""
    }

    fun getSoundDisplayName(packageName: String): String {
        return prefs.getString(KEY_SOUND_NAME_PREFIX + packageName, "") ?: ""
    }

    fun setSoundUri(packageName: String, uri: String, displayName: String) {
        prefs.edit()
            .putString(KEY_SOUND_PREFIX + packageName, uri)
            .putString(KEY_SOUND_NAME_PREFIX + packageName, displayName)
            .apply()
    }

    fun removeSoundUri(packageName: String) {
        prefs.edit()
            .remove(KEY_SOUND_PREFIX + packageName)
            .remove(KEY_SOUND_NAME_PREFIX + packageName)
            .apply()
    }

    fun getAllCustomSounds(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(KEY_SOUND_NAME_PREFIX) && value is String && value.isNotEmpty()) {
                val pkg = key.removePrefix(KEY_SOUND_NAME_PREFIX)
                result[pkg] = value
            }
        }
        return result
    }

    /**
     * Check if a notification's content passes the keyword filters.
     */
    fun passesKeywordFilter(title: String, text: String): Boolean {
        val combined = "$title $text"

        val blacklist = getKeywordBlacklist()
        for (pattern in blacklist) {
            try {
                if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(combined)) {
                    return false
                }
            } catch (_: Exception) {
                // Invalid regex, skip
            }
        }

        val whitelist = getKeywordWhitelist()
        if (whitelist.isEmpty()) return true

        for (pattern in whitelist) {
            try {
                if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(combined)) {
                    return true
                }
            } catch (_: Exception) {
                // Invalid regex, skip
            }
        }

        return false
    }
}
