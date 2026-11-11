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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        // If recording mode is currently active, capture user interaction
        if (AutomationRecorderEngine.isRecording) {
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
                val resId = source.viewIdResourceName
                val text = source.text?.toString() ?: event.text.firstOrNull()?.toString()
                val desc = source.contentDescription?.toString()
                val className = source.className?.toString()

                val step = AutomationStep(
                    title = "Click: ${text ?: desc ?: resId ?: className ?: "Button"}",
                    description = "Clicked in $packageName",
                    actionType = ActionType.CLICK_ELEMENT,
                    targetPackage = packageName,
                    selectorResourceId = resId,
                    selectorText = text,
                    selectorContentDescription = desc,
                    selectorClassName = className,
                    clickX = bounds.centerX().toFloat(),
                    clickY = bounds.centerY().toFloat()
                )
                AutomationRecorderEngine.addRecordedStep(step)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val resId = source.viewIdResourceName
                val text = event.text.joinToString(" ")
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

        // 2. Try single text match
        if (!text.isNullOrBlank()) {
            val nodeText = node.text?.toString()
            if (nodeText != null && (nodeText.contains(text, ignoreCase = true) || text.contains(nodeText, ignoreCase = true))) {
                return node
            }
        }

        // 3. Try multi-candidate text matches (e.g. ["Login", "Log in", "Sign in", "تسجيل الدخول", "دخول"])
        if (candidateTexts.isNotEmpty()) {
            val nodeText = node.text?.toString()
            val nodeDesc = node.contentDescription?.toString()
            for (cand in candidateTexts) {
                if (nodeText != null && (nodeText.contains(cand, ignoreCase = true) || cand.contains(nodeText, ignoreCase = true))) {
                    return node
                }
                if (nodeDesc != null && (nodeDesc.contains(cand, ignoreCase = true) || cand.contains(nodeDesc, ignoreCase = true))) {
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
        return clickCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    fun setTextOnNode(node: AccessibilityNodeInfo, text: String): Boolean {
        // Focus first
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
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

    companion object {
        const val TAG = "VoiceAccessibility"
        var instance: VoiceAccessibilityService? = null
        
        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        val isAvailable: Boolean
            get() = instance != null
    }
}
