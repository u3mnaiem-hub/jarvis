package com.voiceagent.automation.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.voiceagent.automation.data.db.VoiceAgentDatabase
import com.voiceagent.automation.engine.AutomationExecutionEngine
import com.voiceagent.automation.engine.AutomationRecorderEngine
import com.voiceagent.automation.engine.ExecutionState
import com.voiceagent.automation.model.ActionType
import com.voiceagent.automation.model.Automation
import com.voiceagent.automation.model.AutomationStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VoiceAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayScope: CoroutineScope? = null
    private var recordingOverlay: RecordingOverlayManager? = null

    /**
     * True from the moment the floating Stop button is tapped until ~300 ms after the overlay is removed.
     * While true, every accessibility event is ignored so nothing related to stopping gets recorded.
     */
    @Volatile
    private var isStoppingFromOverlay = false
    private val clearStoppingFlag = Runnable { isStoppingFromOverlay = false }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceConnected.value = true
        startRecordingOverlayObserver()
        Log.i(TAG, "VoiceAccessibilityService connected successfully")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        stopRecordingOverlayObserver()
        instance = null
        _isServiceConnected.value = false
        Log.i(TAG, "VoiceAccessibilityService unbound")
        return super.onUnbind(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Keep the floating Stop button inside the screen after rotation / resize.
        recordingOverlay?.onConfigurationChanged()
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        super.onTaskRemoved(rootIntent)
        if (com.voiceagent.automation.security.SafeModeManager.isAutoDisableAccessibilityOnExit(applicationContext)) {
            Log.i(TAG, "App task removed — auto-disabling VoiceAccessibilityService")
            disableServiceSelf()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        // If recording mode is currently active (and not paused), capture user interaction
        if (AutomationRecorderEngine.isRecordingActive) {
            // Never record anything produced by the floating Stop button (tap, drag or its removal)
            if (isStoppingFromOverlay) return
            if (recordingOverlay?.isTouchInProgress == true) return
            if (isRecordingOverlayEvent(event)) return
            handleRecordingEvent(event)
        }
    }

    // ==========================================
    // Floating "Stop Recording" overlay
    // ==========================================

    /** Shows the overlay only while a recording is active and no macro playback is running. */
    private fun startRecordingOverlayObserver() {
        stopRecordingOverlayObserver()
        val overlay = RecordingOverlayManager(this) { tapDownTimeMs -> onOverlayStopTapped(tapDownTimeMs) }
        recordingOverlay = overlay
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        overlayScope = scope
        scope.launch {
            combine(
                AutomationRecorderEngine.isRecordingFlow,
                AutomationExecutionEngine.executionState
            ) { isRecording, execState ->
                isRecording && execState !is ExecutionState.Running
            }
                .distinctUntilChanged()
                .collect { shouldShow ->
                    if (shouldShow) overlay.show() else overlay.hide()
                }
        }
    }

    private fun stopRecordingOverlayObserver() {
        overlayScope?.cancel()
        overlayScope = null
        val overlay = recordingOverlay
        recordingOverlay = null
        if (overlay != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) overlay.hide() else mainHandler.post { overlay.hide() }
        }
    }

    private fun isRecordingOverlayEvent(event: AccessibilityEvent): Boolean {
        val overlayWindowId = recordingOverlay?.overlayWindowId ?: RecordingOverlayManager.NO_WINDOW_ID
        if (overlayWindowId != RecordingOverlayManager.NO_WINDOW_ID && event.windowId == overlayWindowId) return true

        val source = event.source ?: return false
        if (overlayWindowId != RecordingOverlayManager.NO_WINDOW_ID && source.windowId == overlayWindowId) return true
        if (RecordingOverlayManager.isOverlayNode(source)) return true
        if (RecordingOverlayManager.isOverlayContentDescription(event.contentDescription)) return true

        if (event.packageName?.toString() == applicationContext.packageName) {
            val window = try { source.window } catch (_: Exception) { null }
            if (window?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return true
        }
        return false
    }

    /** Single tap on the floating button: stop + save the macro while staying on the current app. */
    private fun onOverlayStopTapped(tapDownTimeMs: Long) {
        if (isStoppingFromOverlay) return
        isStoppingFromOverlay = true
        mainHandler.removeCallbacks(clearStoppingFlag)

        if (!AutomationRecorderEngine.isRecording) {
            recordingOverlay?.hide()
            mainHandler.postDelayed(clearStoppingFlag, OVERLAY_STOP_GRACE_MS)
            return
        }

        val ownPackage = applicationContext.packageName
        val rawSteps = AutomationRecorderEngine.stopRecording()
        val cleanedSteps = AutomationRecorderEngine.sanitizeStepsForOverlayStop(
            steps = rawSteps,
            stopTapTimeMs = tapDownTimeMs,
            ownPackage = ownPackage,
            overlayViewIdSuffix = RecordingOverlayManager.OVERLAY_VIEW_ID_SUFFIX,
            overlayContentDescription = RecordingOverlayManager.OVERLAY_CONTENT_DESCRIPTION
        )

        recordingOverlay?.hide()
        mainHandler.postDelayed(clearStoppingFlag, OVERLAY_STOP_GRACE_MS)

        if (cleanedSteps.isEmpty()) {
            AutomationRecorderEngine.cancelRecording()
            Toast.makeText(applicationContext, "No steps recorded – nothing saved", Toast.LENGTH_SHORT).show()
            return
        }

        val name = AutomationRecorderEngine.pendingWorkflowName.trim().ifBlank {
            "Macro " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        }
        val nameAr = AutomationRecorderEngine.pendingWorkflowNameAr.trim().ifBlank { name }
        val phrase = AutomationRecorderEngine.pendingVoicePhrase.trim()
        val automation = Automation(
            name = name,
            nameAr = nameAr,
            voicePhrases = if (phrase.isNotBlank()) listOf(phrase) else emptyList(),
            steps = cleanedSteps
        )
        val appContext = applicationContext

        // Save in the background; never start any Jarvis activity so the user stays on the current app.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                VoiceAgentDatabase.getInstance(appContext).automationDao().insertOrUpdate(automation)
                AutomationRecorderEngine.cancelRecording()
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Macro saved", Toast.LENGTH_SHORT).show()
                }
                Log.i(TAG, "Macro '${automation.name}' saved from overlay with ${cleanedSteps.size} steps")
            } catch (e: Exception) {
                // Keep the (stopped) steps in the recorder so the user can still save from the in-app screen.
                Log.e(TAG, "Failed to save macro from overlay: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Failed to save macro", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleRecordingEvent(event: AccessibilityEvent) {
        val eventType = event.eventType
        val packageName = event.packageName?.toString() ?: return

        // CRITICAL BANKING PROTECTION: Block any event from financial/banking applications immediately
        if (com.voiceagent.automation.security.BankingProtectionManager.isPackageProtected(applicationContext, packageName)) {
            Log.w(TAG, "Banking Protection active: Dropped event from sensitive package $packageName")
            return
        }

        val source = event.source ?: return

        // Skip events coming from our own Voice Agent app UI, EXCEPT inside the In-App Browser WebView
        if (packageName == applicationContext.packageName && !isInsideWebView(source)) {
            return
        }

        val bounds = Rect()
        source.getBoundsInScreen(bounds)

        when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                ensureLaunchStepRecorded(packageName)
                val resId = source.viewIdResourceName
                val rawText = source.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: event.text.firstOrNull()?.toString()?.takeIf { it.isNotBlank() }
                val desc = source.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                val childLabel = if (rawText == null && desc == null) extractFirstChildLabel(source) else null
                val text = rawText ?: childLabel
                val className = source.className?.toString()

                // If clicking an editable input that already has text (e.g. prefilled/autofilled credentials)
                val isEditable = source.isFocusable && (source.className?.contains("EditText", ignoreCase = true) == true || source.isEditable)
                val existingText = source.text?.toString()

                val step = AutomationStep(
                    title = if (isEditable && !existingText.isNullOrBlank()) "Select Field: $existingText" else "Click: ${text ?: desc ?: resId ?: className ?: "Button"}",
                    description = "Clicked in $packageName",
                    actionType = ActionType.CLICK_ELEMENT,
                    targetPackage = packageName,
                    selectorResourceId = resId,
                    selectorText = text,
                    selectorContentDescription = desc,
                    selectorClassName = className,
                    textPayload = if (isEditable && !existingText.isNullOrBlank()) existingText else null,
                    clickX = bounds.centerX().toFloat(),
                    clickY = bounds.centerY().toFloat()
                )
                AutomationRecorderEngine.addRecordedStep(step)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                ensureLaunchStepRecorded(packageName)
                val resId = source.viewIdResourceName
                // Read source.text first if available (contains full string), otherwise event.text
                val text = source.text?.toString() ?: event.text.joinToString("")
                val desc = source.contentDescription?.toString()
                val hint = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    source.hintText?.toString()
                } else null
                val className = source.className?.toString()

                if (text.isNotBlank()) {
                    val step = AutomationStep(
                        title = "Type Text: $text",
                        description = "Entered text in $packageName",
                        actionType = ActionType.SET_TEXT,
                        targetPackage = packageName,
                        selectorResourceId = resId,
                        selectorText = hint,
                        selectorContentDescription = desc,
                        selectorClassName = className,
                        textPayload = text,
                        clickX = bounds.centerX().toFloat(),
                        clickY = bounds.centerY().toFloat()
                    )
                    AutomationRecorderEngine.addRecordedStep(step)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val activityName = event.className?.toString()
                Log.d(TAG, "Window changed: $packageName / $activityName")
            }
        }
    }

    private fun extractFirstChildLabel(node: AccessibilityNodeInfo, depth: Int = 0): String? {
        if (depth > 3) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val t = child.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: child.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }
            if (t != null) return t
            val nested = extractFirstChildLabel(child, depth + 1)
            if (nested != null) return nested
        }
        return null
    }

    fun isInsideWebView(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 12) {
            val cls = current.className?.toString() ?: ""
            if (cls.contains("WebView", ignoreCase = true)) {
                return true
            }
            current = current.parent
            depth++
        }
        return false
    }

    /**
     * Ensures a LAUNCH_APP step is recorded when the user switches into a launchable external app
     * so replaying the recorded workflow doesn't remain stuck inside VoiceAgent's UI.
     */
    private fun ensureLaunchStepRecorded(packageName: String) {
        if (packageName == applicationContext.packageName) return
        val lastPkg = AutomationRecorderEngine.recordedStepsFlow.value.lastOrNull()?.targetPackage
        if (lastPkg == packageName) return

        val homePkg = packageManager.resolveActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
            0
        )?.activityInfo?.packageName
        if (packageName == homePkg || packageName == "com.android.systemui") return

        if (packageManager.getLaunchIntentForPackage(packageName) == null) return

        val appLabel = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        AutomationRecorderEngine.addRecordedStep(
            AutomationStep(
                title = "Open $appLabel",
                description = "Launch $packageName",
                actionType = ActionType.LAUNCH_APP,
                targetPackage = packageName,
                delayAfterMs = 1500L
            )
        )
    }

    override fun onInterrupt() {
        Log.w(TAG, "VoiceAccessibilityService interrupted")
    }

    override fun onDestroy() {
        stopRecordingOverlayObserver()
        mainHandler.removeCallbacks(clearStoppingFlag)
        super.onDestroy()
        instance = null
        _isServiceConnected.value = false
    }

    // ==========================================
    // Core Node Finder & Interaction Utilities
    // ==========================================

    fun extractAllVisibleTexts(): List<String> {
        val texts = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val t = node.text?.toString()?.trim()
            if (!t.isNullOrBlank()) texts.add(t)
            val d = node.contentDescription?.toString()?.trim()
            if (!d.isNullOrBlank() && d != t) texts.add(d)
            for (i in 0 until node.childCount) {
                collect(node.getChild(i))
            }
        }
        rootInActiveWindow?.let { collect(it) }
        try {
            for (w in windows) {
                w.root?.let { collect(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error traversing windows for visible texts: ${e.message}")
        }
        return texts.distinct()
    }

    fun findNode(
        resourceId: String? = null,
        text: String? = null,
        contentDesc: String? = null,
        className: String? = null,
        candidateTexts: List<String> = emptyList()
    ): AccessibilityNodeInfo? {
        // First attempt with rootInActiveWindow
        val root = rootInActiveWindow
        if (root != null) {
            val found = searchNodeRecursively(root, resourceId, text, contentDesc, className, candidateTexts)
            if (found != null) return found
        }

        // Secondary attempt: inspect interactive windows list (crucial for GeckoView / Firefox)
        try {
            val currentWindows = windows
            for (window in currentWindows) {
                val windowRoot = window.root ?: continue
                val found = searchNodeRecursively(windowRoot, resourceId, text, contentDesc, className, candidateTexts)
                if (found != null) return found
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error traversing interactive windows: ${e.message}")
        }

        return null
    }

    private fun searchNodeRecursively(
        node: AccessibilityNodeInfo,
        resId: String?,
        text: String?,
        contentDesc: String?,
        className: String?,
        candidateTexts: List<String> = emptyList()
    ): AccessibilityNodeInfo? {
        // 1. Try resource ID exact match
        if (!resId.isNullOrBlank() && node.viewIdResourceName?.equals(resId, ignoreCase = true) == true) {
            return node
        }

        var selfMatch: AccessibilityNodeInfo? = null

        // 2. Try single text, contentDescription, or placeholder match
        if (!text.isNullOrBlank()) {
            val nodeText = node.text?.toString()?.trim()
            val nodeDesc = node.contentDescription?.toString()?.trim()
            val hintText = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim()
            } else null

            if ((!nodeText.isNullOrBlank() && (nodeText.contains(text, ignoreCase = true) || (nodeText.length >= 2 && text.contains(nodeText, ignoreCase = true)))) ||
                (!nodeDesc.isNullOrBlank() && (nodeDesc.contains(text, ignoreCase = true) || (nodeDesc.length >= 2 && text.contains(nodeDesc, ignoreCase = true)))) ||
                (!hintText.isNullOrBlank() && (hintText.contains(text, ignoreCase = true) || (hintText.length >= 2 && text.contains(hintText, ignoreCase = true))))
            ) {
                selfMatch = node
            }
        }

        // 3. Try multi-candidate text matches (e.g. ["Login", "Log in", "Sign in", "تسجيل الدخول", "دخول"])
        if (selfMatch == null && candidateTexts.isNotEmpty()) {
            val nodeText = node.text?.toString()?.trim()
            val nodeDesc = node.contentDescription?.toString()?.trim()
            val hintText = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim()
            } else null

            for (cand in candidateTexts) {
                if (cand.isBlank()) continue
                if ((!nodeText.isNullOrBlank() && (nodeText.contains(cand, ignoreCase = true) || (nodeText.length >= 2 && cand.contains(nodeText, ignoreCase = true)))) ||
                    (!nodeDesc.isNullOrBlank() && (nodeDesc.contains(cand, ignoreCase = true) || (nodeDesc.length >= 2 && cand.contains(nodeDesc, ignoreCase = true)))) ||
                    (!hintText.isNullOrBlank() && (hintText.contains(cand, ignoreCase = true) || (hintText.length >= 2 && cand.contains(hintText, ignoreCase = true))))
                ) {
                    selfMatch = node
                    break
                }
            }
        }

        // 4. Try content description
        if (selfMatch == null && !contentDesc.isNullOrBlank()) {
            val nodeDesc = node.contentDescription?.toString()
            if (nodeDesc != null && nodeDesc.contains(contentDesc, ignoreCase = true)) {
                selfMatch = node
            }
        }

        // 5. Try class name only for specific widgets (never generic View/Layout containers so circles/icons fall back to coordinates)
        if (selfMatch == null && resId.isNullOrBlank() && text.isNullOrBlank() && contentDesc.isNullOrBlank() && candidateTexts.isEmpty() && !className.isNullOrBlank()) {
            val genericContainers = setOf(
                "android.view.View",
                "android.view.ViewGroup",
                "android.widget.FrameLayout",
                "android.widget.LinearLayout",
                "android.widget.RelativeLayout",
                "android.webkit.WebView"
            )
            if (className !in genericContainers && node.className?.toString()?.equals(className, ignoreCase = true) == true) {
                selfMatch = node
            }
        }

        // Prefer clickable/actionable node if selfMatch is a non-clickable header/label above a real button
        if (selfMatch != null && (selfMatch.isClickable || selfMatch.className?.toString()?.contains("Button", ignoreCase = true) == true)) {
            return selfMatch
        }

        // Recurse children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchNodeRecursively(child, resId, text, contentDesc, className, candidateTexts)
            if (found != null) return found
        }

        return selfMatch
    }

    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                val success = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) return true
            }
            current = current.parent
        }
        // Fallback: click coordinates via gesture
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        return clickCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    fun findFocusedInputNode(): AccessibilityNodeInfo? {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        try {
            for (window in windows) {
                window.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error finding focused input node: ${e.message}")
        }
        return null
    }

    fun getNodeCenter(node: AccessibilityNodeInfo): Pair<Float, Float>? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return null
        return bounds.centerX().toFloat() to bounds.centerY().toFloat()
    }

    fun setTextOnNode(node: AccessibilityNodeInfo, text: String): Boolean {
        // Focus (and click if needed) before setting text for better WebView/browser compatibility
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return true
        }
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun clearTextOnNode(node: AccessibilityNodeInfo): Boolean {
        return setTextOnNode(node, "")
    }

    fun scrollNode(node: AccessibilityNodeInfo, forward: Boolean): Boolean {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return node.performAction(action)
    }

    fun isSoftKeyboardVisible(): Boolean {
        return try {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (_: Exception) {
            false
        }
    }

    fun dismissSoftKeyboard(): Boolean {
        if (!isSoftKeyboardVisible()) return false
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun getScreenSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    fun isPointOnScreen(x: Float, y: Float): Boolean {
        val (w, h) = getScreenSize()
        var usableBottom = h.toFloat()
        try {
            val imeWindow = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            if (imeWindow != null) {
                val imeBounds = Rect()
                imeWindow.getBoundsInScreen(imeBounds)
                if (!imeBounds.isEmpty && imeBounds.top > 0) {
                    usableBottom = minOf(usableBottom, imeBounds.top.toFloat())
                }
            }
        } catch (_: Exception) {}
        return x >= 1f && x <= (w - 1f) && y >= 1f && y <= (usableBottom - 1f)
    }

    fun isNodeVisibleOnScreen(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        return isPointOnScreen(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    fun pressEnterOnFocusedNode(): Boolean {
        val focused = findFocusedInputNode() ?: return false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                return true
            }
        }
        return false
    }

    fun scrollFirstScrollable(forward: Boolean = true): Boolean {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        fun findAndScroll(node: AccessibilityNodeInfo): Boolean {
            if (node.isScrollable && node.performAction(action)) return true
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (findAndScroll(child)) return true
            }
            return false
        }
        rootInActiveWindow?.let { if (findAndScroll(it)) return true }
        try {
            for (window in windows) {
                val wRoot = window.root ?: continue
                if (findAndScroll(wRoot)) return true
            }
        } catch (_: Exception) {}
        return false
    }

    fun clickCoordinates(x: Float, y: Float, onComplete: ((Boolean) -> Unit)? = null): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onComplete?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                onComplete?.invoke(false)
            }
        }, null)
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long, onComplete: ((Boolean) -> Unit)? = null): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(100))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onComplete?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                onComplete?.invoke(false)
            }
        }, null)
    }

    /**
     * Dumps a list of visible elements currently rendered on screen for debugging and trace analysis
     */
    fun dumpVisibleScreenNodes(maxItems: Int = 15): List<String> {
        val result = mutableListOf<String>()
        val root = rootInActiveWindow
        if (root != null) {
            collectVisibleNodeSummary(root, result, maxItems)
        }
        if (result.isEmpty()) {
            try {
                for (window in windows) {
                    val wRoot = window.root ?: continue
                    collectVisibleNodeSummary(wRoot, result, maxItems)
                    if (result.size >= maxItems) break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error collecting visible node summary: ${e.message}")
            }
        }
        return result
    }

    private fun collectVisibleNodeSummary(node: AccessibilityNodeInfo, list: MutableList<String>, max: Int) {
        if (list.size >= max) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val resId = node.viewIdResourceName
        val shortClass = node.className?.toString()?.substringAfterLast('.') ?: "View"

        if (!text.isNullOrBlank() || !desc.isNullOrBlank() || !resId.isNullOrBlank()) {
            val elementDesc = buildString {
                append(shortClass)
                if (!resId.isNullOrBlank()) append(" id=${resId.substringAfterLast('/')}")
                if (!text.isNullOrBlank()) append(" text=\"${text.take(25)}\"")
                if (!desc.isNullOrBlank()) append(" desc=\"${desc.take(25)}\"")
            }
            if (!list.contains(elementDesc)) {
                list.add(elementDesc)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectVisibleNodeSummary(child, list, max)
            if (list.size >= max) break
        }
    }

    companion object {
        const val TAG = "VoiceAccessibility"
        private const val OVERLAY_STOP_GRACE_MS = 300L
        var instance: VoiceAccessibilityService? = null
        
        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        val isAvailable: Boolean
            get() = instance != null

        /**
         * Programmatically disables this AccessibilityService (Android 7.0+ / API 24+).
         * Works without root or WRITE_SECURE_SETTINGS.
         */
        fun disableServiceSelf(): Boolean {
            val svc = instance ?: return false
            return try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    svc.disableSelf()
                    _isServiceConnected.value = false
                    instance = null
                    Log.i(TAG, "VoiceAccessibilityService disabled via disableSelf()")
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to disableSelf(): ${e.message}")
                false
            }
        }
    }
}
