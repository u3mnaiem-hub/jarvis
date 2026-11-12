package com.voiceagent.automation.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.voiceagent.automation.engine.AutomationRecorderEngine
import com.voiceagent.automation.model.ActionType
import com.voiceagent.automation.model.AutomationStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VoiceAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceConnected.value = true
        Log.i(TAG, "VoiceAccessibilityService connected successfully")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        _isServiceConnected.value = false
        Log.i(TAG, "VoiceAccessibilityService unbound")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        // If recording mode is currently active (and not paused), capture user interaction
        if (AutomationRecorderEngine.isRecordingActive) {
            handleRecordingEvent(event)
        }
    }

    private fun handleRecordingEvent(event: AccessibilityEvent) {
        val eventType = event.eventType
        val packageName = event.packageName?.toString() ?: return

        // Skip events coming from our own Voice Agent app
        if (packageName == applicationContext.packageName) return

        // CRITICAL BANKING PROTECTION: Block any event from financial/banking applications immediately
        if (com.voiceagent.automation.security.BankingProtectionManager.isPackageProtected(applicationContext, packageName)) {
            Log.w(TAG, "Banking Protection active: Dropped event from sensitive package $packageName")
            return
        }

        val source = event.source ?: return
        val bounds = Rect()
        source.getBoundsInScreen(bounds)

        when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                ensureLaunchStepRecorded(packageName)
                val resId = source.viewIdResourceName
                val text = source.text?.toString() ?: event.text.firstOrNull()?.toString()
                val desc = source.contentDescription?.toString()
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

                if (text.isNotBlank()) {
                    val step = AutomationStep(
                        title = "Type Text: $text",
                        description = "Entered text in $packageName",
                        actionType = ActionType.SET_TEXT,
                        targetPackage = packageName,
                        selectorResourceId = resId,
                        selectorContentDescription = desc,
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

    /**
     * Ensures a LAUNCH_APP step is recorded when the user switches into a launchable external app
     * so replaying the recorded workflow doesn't remain stuck inside VoiceAgent's UI.
     */
    private fun ensureLaunchStepRecorded(packageName: String) {
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
        super.onDestroy()
        instance = null
        _isServiceConnected.value = false
    }

    // ==========================================
    // Core Node Finder & Interaction Utilities
    // ==========================================

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

        // 2. Try single text or placeholder match
        if (!text.isNullOrBlank()) {
            val nodeText = node.text?.toString()?.trim()
            val hintText = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim()
            } else null

            if (!nodeText.isNullOrBlank() && (nodeText.contains(text, ignoreCase = true) || (nodeText.length >= 2 && text.contains(nodeText, ignoreCase = true)))) {
                return node
            }
            if (!hintText.isNullOrBlank() && (hintText.contains(text, ignoreCase = true) || (hintText.length >= 2 && text.contains(hintText, ignoreCase = true)))) {
                return node
            }
        }

        // 3. Try multi-candidate text matches (e.g. ["Login", "Log in", "Sign in", "تسجيل الدخول", "دخول"])
        if (candidateTexts.isNotEmpty()) {
            val nodeText = node.text?.toString()?.trim()
            val nodeDesc = node.contentDescription?.toString()?.trim()
            val hintText = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim()
            } else null

            for (cand in candidateTexts) {
                if (cand.isBlank()) continue
                if (!nodeText.isNullOrBlank() && (nodeText.contains(cand, ignoreCase = true) || (nodeText.length >= 2 && cand.contains(nodeText, ignoreCase = true)))) {
                    return node
                }
                if (!nodeDesc.isNullOrBlank() && (nodeDesc.contains(cand, ignoreCase = true) || (nodeDesc.length >= 2 && cand.contains(nodeDesc, ignoreCase = true)))) {
                    return node
                }
                if (!hintText.isNullOrBlank() && (hintText.contains(cand, ignoreCase = true) || (hintText.length >= 2 && cand.contains(hintText, ignoreCase = true)))) {
                    return node
                }
            }
        }

        // 4. Try content description
        if (!contentDesc.isNullOrBlank()) {
            val nodeDesc = node.contentDescription?.toString()
            if (nodeDesc != null && nodeDesc.contains(contentDesc, ignoreCase = true)) {
                return node
            }
        }

        // 5. Try class name if no other specific filter
        if (resId.isNullOrBlank() && text.isNullOrBlank() && contentDesc.isNullOrBlank() && candidateTexts.isEmpty() && !className.isNullOrBlank()) {
            if (node.className?.toString()?.equals(className, ignoreCase = true) == true) {
                return node
            }
        }

        // Recurse children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchNodeRecursively(child, resId, text, contentDesc, className, candidateTexts)
            if (found != null) return found
        }

        return null
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
        var instance: VoiceAccessibilityService? = null
        
        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        val isAvailable: Boolean
            get() = instance != null
    }
}
