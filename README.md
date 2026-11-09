# Voice Agent – Advanced Android Personal Automation Assistant
# المساعد الصوتي المؤتمت للأندرويد

An intelligent, programmable personal automation agent for native Android built with **Kotlin** and **Jetpack Compose**, inspired by MacroDroid, Tasker, and modern autonomous AI agents.

---

## 🌟 Key Architecture & Features

### 1. Automation Recorder Engine (`AutomationRecorderEngine.kt`)
- Intercepts supported user interactions via `AccessibilityService` events (`TYPE_VIEW_CLICKED`, `TYPE_VIEW_TEXT_CHANGED`, `TYPE_WINDOW_STATE_CHANGED`).
- Captures UI elements by **Resource ID**, **Text**, **Content Description**, **Class Name**, and **Screen Bounds/Coordinates** as fallback.
- Deduplicates rapid redundant taps.
- Allows inserting manual steps, custom delays, and conditional logic.

### 2. Sequential Execution Engine (`AutomationExecutionEngine.kt`)
- Reliable sequential execution with timeout, retry backoff, and failure recovery.
- Dynamic element detection: waits for the expected UI element rather than relying only on arbitrary delays.
- Supports 20+ action types:
  - `LAUNCH_APP` (e.g. Firefox, Settings, WhatsApp)
  - `OPEN_URL` (direct browser routing to local router IPs e.g. `http://192.168.1.1`)
  - `CLICK_ELEMENT` & `CLICK_COORDINATE`
  - `SET_TEXT` & `CLEAR_TEXT`
  - `INJECT_CREDENTIAL` (retrieves encrypted passwords from Keystore)
  - `WAIT_FOR_ELEMENT`, `DELAY`, `SCROLL_FORWARD`, `SWIPE`, `PRESS_BACK`
  - Conditional checks (`CHECK_ELEMENT_EXISTS`, `CHECK_TEXT_EXISTS`)
  - Spoken TTS feedback and status notifications.
- Emergency user stop (`requestStop()`).

### 3. Smart Credential Manager (`KeystoreManager.kt`)
- Passwords (e.g., router admin credentials) are **never stored as plaintext**.
- Encrypted using **Android Keystore AES-256 GCM**.
- Passwords are never displayed in execution logs, and decrypted only in-memory at the exact millisecond of injection into the target field.

### 4. Intelligent Voice Control (`CommandMatchingEngine.kt`)
- Direct microphone activation with real-time `SpeechRecognizer`.
- Supports **Egyptian Arabic (`ar-EG`)**, Modern Standard Arabic (`ar-SA`), and English (`en-US`).
- Intelligent fuzzy phrase matching with Arabic normalization (handling diacritics, Alef variants, Teh Marbuta, Levenshtein distance, token overlap).
- Ambiguity detection: asks the user if two automations have similar phrases.

### 5. First Real-World Test Workflow: "Enable TV Internet"
Pre-seeded and fully editable in `DemoWorkflows.kt`:
1. Launch Browser (`org.mozilla.firefox`).
2. Navigate to router IP (`http://192.168.1.1`).
3. Wait for router login page.
4. Locate & enter username (`admin`).
5. Retrieve & inject authorized password from Android Keystore.
6. Press Login button.
7. Wait for dashboard status page.
8. Navigate to Parental Control.
9. Select Connected Devices.
10. Locate Television (`Smart-TV`).
11. Toggle internet access to Enabled.
12. Press Save changes.
13. Verify new status is Enabled.
14. Announce spoken confirmation.

---

## 📁 Project Structure

```
VoiceAgent/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/
│       │   ├── xml/accessibility_service_config.xml
│       │   ├── values/strings.xml, colors.xml, themes.xml
│       │   └── values-ar/strings.xml
│       └── java/com/voiceagent/automation/
│           ├── VoiceAgentApp.kt                   # Application entrypoint & seeding
│           ├── MainActivity.kt                   # Jetpack Compose UI & Bottom Navigation
│           ├── model/
│           │   ├── ActionType.kt
│           │   ├── Automation.kt
│           │   ├── AutomationStep.kt
│           │   ├── ExecutionLog.kt
│           │   └── Credential.kt
│           ├── data/db/
│           │   ├── VoiceAgentDatabase.kt         # Room Database
│           │   ├── AutomationDao.kt
│           │   ├── ExecutionLogDao.kt
│           │   ├── CredentialDao.kt
│           │   └── Converters.kt
│           ├── service/
│           │   ├── VoiceAccessibilityService.kt   # Core AccessibilityService
│           │   └── AutomationForegroundService.kt# Ongoing notification with Stop button
│           ├── engine/
│           │   ├── AutomationExecutionEngine.kt  # Sequential runner
│           │   ├── AutomationRecorderEngine.kt   # Action recorder
│           │   └── ExecutionResult.kt
│           ├── voice/
│           │   ├── VoiceRecognitionManager.kt    # SpeechRecognizer wrapper
│           │   ├── CommandMatchingEngine.kt      # Arabic/English phrase matcher
│           │   └── TextToSpeechManager.kt        # Arabic/English TTS feedback
│           ├── security/
│           │   └── KeystoreManager.kt            # AES-256 GCM Keystore encryption
│           ├── template/
│           │   └── DemoWorkflows.kt              # Pre-configured router workflows
│           └── ui/
│               ├── theme/                        # Dark & Light Material3 themes
│               ├── navigation/Screen.kt
│               └── screens/
│                   ├── HomeScreen.kt             # Hero mic, quick run, recent status
│                   ├── MyAutomationsScreen.kt    # Card library, categories, search
│                   ├── WorkflowEditorScreen.kt   # Step-by-step editor
│                   ├── RecorderScreen.kt         # Live recorder
│                   ├── ExecutionHistoryScreen.kt # Step audit with checkmarks
│                   ├── CredentialManagerScreen.kt# Keystore passwords
│                   └── SettingsScreen.kt         # Accessibility status, TTS settings
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew & gradlew.bat
└── README.md
```

---

## 🚀 How to Open and Build in Android Studio

1. Launch **Android Studio** (Hedgehog, Iguana, Jellyfish, or newer).
2. Click **Open** and select the folder:
   ```
   D:\Downloads\antigravity\VoiceAgent
   ```
3. Let Gradle sync and download dependencies.
4. Connect your Android device via USB (or start an Android Emulator with API 26+).
5. Press **Run (Shift + F10)** or build the APK via:
   ```
   Menu -> Build -> Build Bundle(s) / APK(s) -> Build APK(s)
   ```
6. Install the resulting `app-debug.apk` onto your phone.

---

## ⚙️ Initial Setup & Permissions on Phone

1. **Accessibility Permission (Crucial)**:
   - Go to your phone's **Settings -> Accessibility -> Installed Apps (الخدمات المثبتة)**.
   - Locate **Voice Agent Automation Service** and toggle it **ON**.
2. **Microphone Permission**:
   - When launching the app, grant the Audio Recording permission when prompted so the voice recognition engine can listen to commands.
3. **Display over other apps (Optional for floating controls)**:
   - Enable "Appear on top" in app settings if you wish to see floating recording indicators over other apps.

---

## 🗣️ Voice Command Examples

- **Router & Internet**:
  - *"شغل النت للتلفزيون"* / *"Enable TV internet"*
  - *"اقفل النت عن التلفزيون"* / *"Disable TV internet"*
  - *"افتح صفحة الراوتر"* / *"Open router page"*
- **Navigation & Apps**:
  - *"افتح فايرفوكس"* / *"Open Firefox"*
  - *"افتح إعدادات الواي فاي"* / *"Open Wi-Fi settings"*
- **Workflow Control**:
  - *"وقف التنفيذ"* / *"Stop automation"*
