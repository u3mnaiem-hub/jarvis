package com.voiceagent.automation.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Helper to manage Jarvis's AccessibilityService from inside the app:
 * - Disabling works programmatically on Android 7+ via disableSelf() with zero extra permissions.
 * - Enabling works programmatically if WRITE_SECURE_SETTINGS is granted (e.g. via ADB),
 *   otherwise opens Android's Accessibility Settings directly.
 */
object AccessibilityControlHelper {

    fun hasWriteSecureSettingsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Attempts to enable VoiceAccessibilityService programmatically if WRITE_SECURE_SETTINGS
     * is granted; otherwise opens the system Accessibility Settings screen.
     * Returns true if enabled silently, false if settings screen was opened.
     */
    fun enableOrOpenSettings(context: Context): Boolean {
        if (hasWriteSecureSettingsPermission(context)) {
            try {
                val component = ComponentName(context, VoiceAccessibilityService::class.java).flattenToString()
                val existing = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                val updated = if (existing.isBlank()) {
                    component
                } else if (!existing.split(":").contains(component)) {
                    "$existing:$component"
                } else {
                    existing
                }
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    updated
                )
                Settings.Secure.putInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    1
                )
                return true
            } catch (_: Exception) {
                // Fall through to opening Settings
            }
        }

        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return false
    }

    /**
     * Disables VoiceAccessibilityService immediately using disableSelf(),
     * and also removes it from Settings.Secure if WRITE_SECURE_SETTINGS is granted.
     */
    fun disableNow(context: Context): Boolean {
        val selfDisabled = VoiceAccessibilityService.disableServiceSelf()
        if (hasWriteSecureSettingsPermission(context)) {
            try {
                val component = ComponentName(context, VoiceAccessibilityService::class.java).flattenToString()
                val existing = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                val filtered = existing.split(":")
                    .filter { it.isNotBlank() && it != component }
                    .joinToString(":")
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    filtered
                )
                return true
            } catch (_: Exception) {
            }
        }
        return selfDisabled
    }
}
