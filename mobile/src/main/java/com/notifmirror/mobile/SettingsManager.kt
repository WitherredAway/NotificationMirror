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
        private const val KEY_DEFAULT_VIBRATION = "default_vibration_pattern"
        private const val KEY_ONGOING_MODE = "ongoing_mode"

        // Ongoing/persistent notification modes
        const val ONGOING_NONE = 0
        const val ONGOING_ONLY = 1
        const val ONGOING_ALL_PERSISTENT = 2
        private const val KEY_NOTIF_PRIORITY = "notification_priority"
        private const val KEY_BIG_TEXT_THRESHOLD = "big_text_threshold"
        private const val KEY_AUTO_CANCEL = "auto_cancel"
        private const val KEY_AUTO_DISMISS_SYNC = "auto_dismiss_sync"
        private const val KEY_SHOW_OPEN_BUTTON = "show_open_on_watch"
        private const val KEY_SHOW_MUTE_BUTTON = "show_mute_button"
        private const val KEY_AUTO_UPDATE = "auto_update_enabled"
        private const val KEY_KEEP_NOTIFICATION_HISTORY = "keep_notification_history"
        private const val KEY_HIDE_WHEN_LOCKED = "hide_content_when_locked"
        private const val KEY_MUTE_CONTINUATION = "mute_continuation_alerts"
        private const val KEY_SHOW_SNOOZE_BUTTON = "show_snooze_button"
        private const val KEY_SNOOZE_DURATION = "snooze_duration_minutes"
        private const val KEY_BATTERY_SAVER_ENABLED = "battery_saver_enabled"
        private const val KEY_BATTERY_SAVER_THRESHOLD = "battery_saver_threshold"
        private const val KEY_COMPLICATION_SOURCE = "complication_source"
        private const val KEY_COMPLICATION_APP = "complication_app"
        private const val KEY_MIRRORING_ENABLED = "mirroring_enabled"

        // Screen off modes
        const val SCREEN_MODE_ALWAYS = 0
        const val SCREEN_MODE_SCREEN_OFF_ONLY = 1
        const val SCREEN_MODE_SILENT_WHEN_ON = 2
        const val SCREEN_MODE_VIBRATE_ONLY_WHEN_ON = 3

        // Notification priority values
        const val PRIORITY_HIGH = 1
        const val PRIORITY_DEFAULT = 0
        const val PRIORITY_LOW = -1
    }

    internal val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Cache compiled Regex patterns to avoid re-compiling on every notification
    private val regexCache = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val INVALID_REGEX_SENTINEL = Any()

    private fun getCachedRegex(pattern: String): Regex? {
        val cached = regexCache[pattern]
        if (cached != null) {
            return if (cached === INVALID_REGEX_SENTINEL) null else cached as Regex
        }
        return try {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            regexCache[pattern] = regex
            regex
        } catch (_: Exception) {
            regexCache[pattern] = INVALID_REGEX_SENTINEL
            null
        }
    }

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

    // --- Ongoing/Persistent Mode (unified 3-option selector) ---

    fun getOngoingMode(): Int = prefs.getInt(KEY_ONGOING_MODE, ONGOING_NONE)

    fun setOngoingMode(mode: Int) {
        prefs.edit().putInt(KEY_ONGOING_MODE, mode).apply()
    }

    // --- Notification Priority ---

    fun getNotificationPriority(): Int = prefs.getInt(KEY_NOTIF_PRIORITY, PRIORITY_HIGH)

    fun setNotificationPriority(priority: Int) {
        prefs.edit().putInt(KEY_NOTIF_PRIORITY, priority).apply()
    }

    // --- BigText Threshold ---

    fun getBigTextThreshold(): Int = prefs.getInt(KEY_BIG_TEXT_THRESHOLD, 1)

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

    // --- Auto Update ---

    fun isAutoUpdateEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_UPDATE, false)

    fun setAutoUpdateEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_UPDATE, enabled).apply()
    }

    // --- Notification History ---

    fun isKeepNotificationHistoryEnabled(): Boolean = prefs.getBoolean(KEY_KEEP_NOTIFICATION_HISTORY, true)

    fun setKeepNotificationHistoryEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_NOTIFICATION_HISTORY, enabled).apply()
    }

    // --- Hide Content When Locked ---

    fun isHideWhenLockedEnabled(): Boolean = prefs.getBoolean(KEY_HIDE_WHEN_LOCKED, false)

    fun setHideWhenLockedEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HIDE_WHEN_LOCKED, enabled).apply()
    }

    // --- Mute Continuation Alerts ---

    fun isMuteContinuationEnabled(): Boolean = prefs.getBoolean(KEY_MUTE_CONTINUATION, false)

    fun setMuteContinuationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MUTE_CONTINUATION, enabled).apply()
    }

    // --- Snooze Button ---

    fun isShowSnoozeButtonEnabled(): Boolean = prefs.getBoolean(KEY_SHOW_SNOOZE_BUTTON, true)

    fun setShowSnoozeButtonEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_SNOOZE_BUTTON, enabled).apply()
    }

    // --- Snooze Duration ---

    fun getSnoozeDurationMinutes(): Int = prefs.getInt(KEY_SNOOZE_DURATION, 5)

    fun setSnoozeDurationMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_SNOOZE_DURATION, minutes).apply()
    }

    // --- Battery Saver ---

    fun isBatterySaverEnabled(): Boolean = prefs.getBoolean(KEY_BATTERY_SAVER_ENABLED, false)

    fun setBatterySaverEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BATTERY_SAVER_ENABLED, enabled).apply()
    }

    fun getBatterySaverThreshold(): Int = prefs.getInt(KEY_BATTERY_SAVER_THRESHOLD, 15)

    fun setBatterySaverThreshold(threshold: Int) {
        prefs.edit().putInt(KEY_BATTERY_SAVER_THRESHOLD, threshold).apply()
    }

    // --- Mirroring Toggle ---

    fun isMirroringEnabled(): Boolean = prefs.getBoolean(KEY_MIRRORING_ENABLED, true)

    fun setMirroringEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MIRRORING_ENABLED, enabled).apply()
    }

    // --- Complication Settings ---

    fun getComplicationSource(): String = prefs.getString(KEY_COMPLICATION_SOURCE, "most_recent") ?: "most_recent"

    fun setComplicationSource(source: String) {
        prefs.edit().putString(KEY_COMPLICATION_SOURCE, source).apply()
    }

    fun getComplicationApp(): String = prefs.getString(KEY_COMPLICATION_APP, "") ?: ""

    fun setComplicationApp(packageName: String) {
        prefs.edit().putString(KEY_COMPLICATION_APP, packageName).apply()
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

    // --- Per-App Settings (null/empty = use global default) ---

    private fun perAppKey(setting: String, packageName: String): String {
        return "per_app_${setting}_$packageName"
    }

    private fun perAppEnabledKey(setting: String, packageName: String): String {
        return "per_app_${setting}_enabled_$packageName"
    }

    // Per-app boolean setting with "use default" support
    fun getPerAppBoolean(setting: String, packageName: String, globalDefault: Boolean): Boolean {
        val key = perAppKey(setting, packageName)
        val enabledKey = perAppEnabledKey(setting, packageName)
        return if (prefs.getBoolean(enabledKey, false)) {
            prefs.getBoolean(key, globalDefault)
        } else {
            globalDefault
        }
    }

    fun setPerAppBoolean(setting: String, packageName: String, value: Boolean) {
        prefs.edit()
            .putBoolean(perAppEnabledKey(setting, packageName), true)
            .putBoolean(perAppKey(setting, packageName), value)
            .apply()
    }

    fun clearPerAppBoolean(setting: String, packageName: String) {
        prefs.edit()
            .remove(perAppEnabledKey(setting, packageName))
            .remove(perAppKey(setting, packageName))
            .apply()
    }

    fun isPerAppBooleanCustomized(setting: String, packageName: String): Boolean {
        return prefs.getBoolean(perAppEnabledKey(setting, packageName), false)
    }

    // Per-app int setting with "use default" support
    fun getPerAppInt(setting: String, packageName: String, globalDefault: Int): Int {
        val key = perAppKey(setting, packageName)
        val enabledKey = perAppEnabledKey(setting, packageName)
        return if (prefs.getBoolean(enabledKey, false)) {
            prefs.getInt(key, globalDefault)
        } else {
            globalDefault
        }
    }

    fun setPerAppInt(setting: String, packageName: String, value: Int) {
        prefs.edit()
            .putBoolean(perAppEnabledKey(setting, packageName), true)
            .putInt(perAppKey(setting, packageName), value)
            .apply()
    }

    fun clearPerAppInt(setting: String, packageName: String) {
        prefs.edit()
            .remove(perAppEnabledKey(setting, packageName))
            .remove(perAppKey(setting, packageName))
            .apply()
    }

    fun isPerAppIntCustomized(setting: String, packageName: String): Boolean {
        return prefs.getBoolean(perAppEnabledKey(setting, packageName), false)
    }

    // Effective per-app getters (check per-app first, then global)
    fun getEffectivePriority(packageName: String): Int {
        return getPerAppInt("priority", packageName, getNotificationPriority())
    }

    fun getEffectiveOngoingMode(packageName: String): Int {
        return getPerAppInt("ongoing_mode", packageName, getOngoingMode())
    }

    fun getEffectiveAutoCancel(packageName: String): Boolean {
        return getPerAppBoolean("auto_cancel", packageName, isAutoCancelEnabled())
    }

    fun getEffectiveAutoDismissSync(packageName: String): Boolean {
        return getPerAppBoolean("auto_dismiss", packageName, isAutoDismissSyncEnabled())
    }

    fun getEffectiveShowOpenButton(packageName: String): Boolean {
        return getPerAppBoolean("show_open", packageName, isShowOpenButtonEnabled())
    }

    fun getEffectiveShowMuteButton(packageName: String): Boolean {
        return getPerAppBoolean("show_mute", packageName, isShowMuteButtonEnabled())
    }

    fun getEffectiveMuteDuration(packageName: String): Int {
        return getPerAppInt("mute_duration", packageName, getMuteDurationMinutes())
    }

    fun getEffectiveScreenOffMode(packageName: String): Int {
        return getPerAppInt("screen_off_mode", packageName, getScreenOffMode())
    }

    fun getEffectiveMuteContinuation(packageName: String): Boolean {
        return getPerAppBoolean("mute_continuation", packageName, isMuteContinuationEnabled())
    }

    fun getEffectiveShowSnoozeButton(packageName: String): Boolean {
        return getPerAppBoolean("show_snooze", packageName, isShowSnoozeButtonEnabled())
    }

    fun getEffectiveSnoozeDuration(packageName: String): Int {
        return getPerAppInt("snooze_duration", packageName, getSnoozeDurationMinutes())
    }

    fun getEffectiveBigTextThreshold(packageName: String): Int {
        return getPerAppInt("big_text_threshold", packageName, getBigTextThreshold())
    }

    fun getEffectiveVibrationPattern(packageName: String): String {
        val custom = getVibrationPattern(packageName)
        return if (custom.isNotEmpty()) custom else getDefaultVibrationPattern()
    }

    // --- Per-App Keyword Filters ---

    fun getPerAppKeywordWhitelist(packageName: String): List<String> {
        val raw = prefs.getString("per_app_keyword_whitelist_$packageName", "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split("\n").filter { it.isNotBlank() }
    }

    fun setPerAppKeywordWhitelist(packageName: String, patterns: List<String>) {
        prefs.edit().putString("per_app_keyword_whitelist_$packageName", patterns.joinToString("\n")).apply()
    }

    fun getPerAppKeywordBlacklist(packageName: String): List<String> {
        val raw = prefs.getString("per_app_keyword_blacklist_$packageName", "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split("\n").filter { it.isNotBlank() }
    }

    fun setPerAppKeywordBlacklist(packageName: String, patterns: List<String>) {
        prefs.edit().putString("per_app_keyword_blacklist_$packageName", patterns.joinToString("\n")).apply()
    }

    fun clearPerAppKeywordFilters(packageName: String) {
        prefs.edit()
            .remove("per_app_keyword_whitelist_$packageName")
            .remove("per_app_keyword_blacklist_$packageName")
            .apply()
    }

    /**
     * Check if a notification passes per-app keyword filters.
     * Per-app filters are checked AFTER global filters.
     * Returns true if notification should be mirrored.
     */
    fun passesPerAppKeywordFilter(packageName: String, title: String, text: String): Boolean {
        val combined = "$title $text"

        val blacklist = getPerAppKeywordBlacklist(packageName)
        for (pattern in blacklist) {
            val regex = getCachedRegex(pattern) ?: continue
            if (regex.containsMatchIn(combined)) return false
        }

        val whitelist = getPerAppKeywordWhitelist(packageName)
        if (whitelist.isEmpty()) return true

        for (pattern in whitelist) {
            val regex = getCachedRegex(pattern) ?: continue
            if (regex.containsMatchIn(combined)) return true
        }

        return false
    }

    // Check if any per-app setting is customized
    fun hasAnyPerAppCustomization(packageName: String): Boolean {
        val settings = listOf("priority", "mirror_ongoing", "mirror_persistent", "ongoing_mode", "auto_cancel",
            "auto_dismiss", "show_open", "show_mute", "mute_duration", "big_text_threshold",
            "screen_off_mode", "mute_continuation", "show_snooze", "snooze_duration")
        for (s in settings) {
            if (prefs.getBoolean(perAppEnabledKey(s, packageName), false)) return true
        }
        if (getVibrationPattern(packageName).isNotEmpty()) return true
        if (getPerAppKeywordWhitelist(packageName).isNotEmpty()) return true
        if (getPerAppKeywordBlacklist(packageName).isNotEmpty()) return true
        return false
    }

    // Clear all per-app settings
    fun clearAllPerAppSettings(packageName: String) {
        val settings = listOf("priority", "mirror_ongoing", "mirror_persistent", "ongoing_mode", "auto_cancel",
            "auto_dismiss", "show_open", "show_mute", "mute_duration", "big_text_threshold",
            "screen_off_mode", "mute_continuation", "show_snooze", "snooze_duration")
        val editor = prefs.edit()
        for (s in settings) {
            editor.remove(perAppEnabledKey(s, packageName))
            editor.remove(perAppKey(s, packageName))
        }
        editor.apply()
        removeVibrationPattern(packageName)
        clearPerAppKeywordFilters(packageName)
    }

    /**
     * Check if a notification's content passes the keyword filters.
     */
    fun passesKeywordFilter(title: String, text: String): Boolean {
        val combined = "$title $text"

        val blacklist = getKeywordBlacklist()
        for (pattern in blacklist) {
            val regex = getCachedRegex(pattern) ?: continue
            if (regex.containsMatchIn(combined)) return false
        }

        val whitelist = getKeywordWhitelist()
        if (whitelist.isEmpty()) return true

        for (pattern in whitelist) {
            val regex = getCachedRegex(pattern) ?: continue
            if (regex.containsMatchIn(combined)) return true
        }

        return false
    }
}
