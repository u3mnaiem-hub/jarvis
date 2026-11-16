package com.voiceagent.automation.security

import android.content.Context
import com.voiceagent.automation.data.db.VoiceAgentDatabase
import com.voiceagent.automation.model.Credential

/**
 * Shared helper for securely saving, loading, and validating encrypted router credentials.
 */
object RouterCredentialsHelper {

    const val PREFS_NAME = "voice_agent_prefs"
    const val KEY_ROUTER_USER = "router_admin_user"
    const val KEY_ROUTER_PASS = "router_admin_pass"
    const val KEY_ROUTER_PASS_ALT = "router_password"
    const val DEFAULT_SEED_LABEL = "Router Admin Password (Default)"

    const val MISSING_CREDENTIALS_ERROR =
        "لا توجد بيانات دخول الراوتر بالتطبيق. يرجى إدخال اسم المستخدم وكلمة المرور وحفظهما مشفرين أولاً."

    suspend fun saveRouterCredentials(
        context: Context,
        db: VoiceAgentDatabase,
        username: String,
        password: String
    ): String {
        val cleanUser = username.trim()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ROUTER_USER, cleanUser).apply()

        val encUser = KeystoreManager.encrypt(cleanUser)
        db.credentialDao().insertOrUpdate(
            Credential(
                key = KEY_ROUTER_USER,
                label = "ZTE Router Admin Username",
                encryptedData = encUser.encryptedBase64,
                iv = encUser.ivBase64
            )
        )

        val encPass = KeystoreManager.encrypt(password)
        db.credentialDao().insertOrUpdate(
            Credential(
                key = KEY_ROUTER_PASS,
                label = "ZTE Router Admin Password",
                encryptedData = encPass.encryptedBase64,
                iv = encPass.ivBase64
            )
        )
        db.credentialDao().insertOrUpdate(
            Credential(
                key = KEY_ROUTER_PASS_ALT,
                label = "Router Password",
                encryptedData = encPass.encryptedBase64,
                iv = encPass.ivBase64
            )
        )
        return cleanUser
    }

    suspend fun getRouterCredentials(
        context: Context,
        db: VoiceAgentDatabase,
        credentialKey: String = KEY_ROUTER_PASS
    ): Pair<String, String>? {
        val passCred = db.credentialDao().getCredentialByKey(credentialKey)
            ?: db.credentialDao().getCredentialByKey(KEY_ROUTER_PASS)
            ?: db.credentialDao().getCredentialByKey(KEY_ROUTER_PASS_ALT)
            ?: return null

        if (passCred.label == DEFAULT_SEED_LABEL) {
            return null
        }

        val userCred = db.credentialDao().getCredentialByKey(KEY_ROUTER_USER)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val username = if (userCred != null) {
            KeystoreManager.decrypt(userCred.encryptedData, userCred.iv).trim()
        } else {
            prefs.getString(KEY_ROUTER_USER, null)?.trim().orEmpty()
        }

        if (username.isBlank()) {
            return null
        }

        val password = KeystoreManager.decrypt(passCred.encryptedData, passCred.iv)
        if (password.isBlank()) {
            return null
        }

        return username to password
    }
}
