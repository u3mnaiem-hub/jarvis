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
                val existingEnable = db.automationDao().getAutomationById("demo_enable_tv_internet")
                if (existingEnable == null || existingEnable.steps.none { it.isWebStep }) {
                    val enableTvWorkflow = DemoWorkflows.createEnableTvInternetWorkflow()
                    val disableTvWorkflow = DemoWorkflows.createDisableTvInternetWorkflow()
                    db.automationDao().insertAll(listOf(enableTvWorkflow, disableTvWorkflow))
                    Log.i("VoiceAgentApp", "Seeded/upgraded WebMacro demo workflows: Enable & Disable TV Internet")
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
        lateinit var instance: VoiceAgentApp
            private set
    }
}
