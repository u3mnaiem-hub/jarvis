package com.voiceagent.automation.security

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages operating modes:
 * 1. Safe Mode (Default): Works without requiring Accessibility permission or Overlay.
 *    Supports voice commands, app launching, URL opening, Android Intents, Deep Links (e.g. Masrofy), TTS.
 * 2. Advanced Automation Mode: Allows AccessibilityService for user-approved multi-app UI automation.
 */
object SafeModeManager {
    private const val PREFS_NAME = "safe_mode_prefs"
    private const val KEY_ADVANCED_MODE_ENABLED = "advanced_automation_mode_enabled"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * True if user explicitly enabled Advanced Automation Mode.
     * Defaults to false (Safe Mode active).
     */
    fun isAdvancedModeEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ADVANCED_MODE_ENABLED, false)
    }

    fun setAdvancedModeEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ADVANCED_MODE_ENABLED, enabled).apply()
    }
}
