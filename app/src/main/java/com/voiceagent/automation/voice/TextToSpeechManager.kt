package com.voiceagent.automation.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

object TextToSpeechManager {
    private const val TAG = "TextToSpeechManager"
    private var tts: TextToSpeech? = null
    @Volatile
    private var isInitialized = false
    @Volatile
    private var pendingSpeech: Pair<String, Boolean>? = null

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                val arabicLocale = Locale("ar", "EG")
                val result = tts?.setLanguage(arabicLocale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    val fallback = tts?.setLanguage(Locale("ar"))
                    if (fallback == TextToSpeech.LANG_MISSING_DATA || fallback == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.language = Locale.US
                    }
                }
                Log.i(TAG, "TTS initialized successfully")
                pendingSpeech?.let { (text, isAr) ->
                    pendingSpeech = null
                    speak(text, isAr)
                }
            } else {
                Log.e(TAG, "TTS initialization failed: $status")
            }
        }
    }

    fun speak(text: String, isArabic: Boolean = true) {
        if (text.isBlank()) return
        if (!isInitialized || tts == null) {
            pendingSpeech = text to isArabic
            Log.w(TAG, "TTS not yet initialized; queued speech")
            return
        }
        try {
            val targetLocale = if (isArabic) Locale("ar", "EG") else Locale.US
            val res = tts?.setLanguage(targetLocale)
            if (isArabic && (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED)) {
                val arGeneric = tts?.setLanguage(Locale("ar"))
                if (arGeneric == TextToSpeech.LANG_MISSING_DATA || arGeneric == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.getDefault()
                }
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
