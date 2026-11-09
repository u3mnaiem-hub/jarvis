package com.voiceagent.automation.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

object TextToSpeechManager {
    private const val TAG = "TextToSpeechManager"
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                val arabicLocale = Locale("ar")
                val result = tts?.setLanguage(arabicLocale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.US
                }
                Log.i(TAG, "TTS initialized successfully")
            } else {
                Log.e(TAG, "TTS initialization failed: $status")
            }
        }
    }

    fun speak(text: String, isArabic: Boolean = true) {
        if (!isInitialized || tts == null) return
        try {
            if (isArabic) {
                tts?.language = Locale("ar")
            } else {
                tts?.language = Locale.US
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "VoiceAgent_TTS_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak error: ${e.message}")
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
