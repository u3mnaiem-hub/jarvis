package com.voiceagent.automation

import android.app.Application
import android.util.Log
import com.voiceagent.automation.data.db.VoiceAgentDatabase
import com.voiceagent.automation.model.Credential
import com.voiceagent.automation.security.KeystoreManager
import com.voiceagent.automation.template.DemoWorkflows
import com.voiceagent.automation.voice.TextToSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VoiceAgentApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize Text-To-Speech
        TextToSpeechManager.init(this)

        // Seed initial demo workflows and default router credentials if first run
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = VoiceAgentDatabase.getInstance(this@VoiceAgentApp)
                val prefs = getSharedPreferences("workflow_seed", MODE_PRIVATE)
                val seededVersion = prefs.getInt("router_workflows_version", 0)
                val existingEnable = db.automationDao().getAutomationById("demo_enable_tv_internet")
                val existingDisable = db.automationDao().getAutomationById("demo_disable_tv_internet")
                if (seededVersion < ROUTER_WORKFLOWS_VERSION ||
                    existingEnable == null || existingDisable == null ||
                    existingEnable.steps.none { it.actionType == com.voiceagent.automation.model.ActionType.SET_INDEXED_RADIO }
                ) {
                    val customDevice = (existingDisable ?: existingEnable)?.steps
                        ?.firstOrNull { !it.rowContextText.isNullOrBlank() }
                        ?.rowContextText
                        ?: "Smart-TV"
                    val enableTvWorkflow = DemoWorkflows.createEnableTvInternetWorkflow(deviceName = customDevice)
                    val disableTvWorkflow = DemoWorkflows.createDisableTvInternetWorkflow(deviceName = customDevice)
                    db.automationDao().insertAll(listOf(enableTvWorkflow, disableTvWorkflow))
                    prefs.edit().putInt("router_workflows_version", ROUTER_WORKFLOWS_VERSION).apply()
                    Log.i("VoiceAgentApp", "Seeded/upgraded ZTE H188 WebMacro workflows (v$ROUTER_WORKFLOWS_VERSION) for device: $customDevice")
                }

                // Check router credential seed
                val credKey = "router_admin_pass"
                val existingCred = db.credentialDao().getCredentialByKey(credKey)
                if (existingCred == null) {
                    val encrypted = KeystoreManager.encrypt("admin123")
                    db.credentialDao().insertOrUpdate(
                        Credential(
                            key = credKey,
                            label = "Router Admin Password (Default)",
                            encryptedData = encrypted.encryptedBase64,
                            iv = encrypted.ivBase64
                        )
                    )
                    Log.i("VoiceAgentApp", "Created secure Keystore-encrypted router credential placeholder")
                }
            } catch (e: Exception) {
                Log.e("VoiceAgentApp", "Error during app initialization: ${e.message}")
            }
        }
    }

    companion object {
        /** Bump to force re-seeding of the built-in router workflows on next app start. */
        private const val ROUTER_WORKFLOWS_VERSION = 2

        lateinit var instance: VoiceAgentApp
            private set
    }
}
