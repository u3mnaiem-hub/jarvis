package com.voiceagent.automation.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.voiceagent.automation.data.db.VoiceAgentDatabase
import com.voiceagent.automation.model.*
import com.voiceagent.automation.security.KeystoreManager
import com.voiceagent.automation.service.AutomationForegroundService
import com.voiceagent.automation.service.VoiceAccessibilityService
import com.voiceagent.automation.voice.TextToSpeechManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AutomationExecutionEngine {
    private const val TAG = "AutomationEngine"

    private val _executionState = MutableStateFlow<ExecutionState>(ExecutionState.Idle)
    val executionState: StateFlow<ExecutionState> = _executionState.asStateFlow()

    @Volatile
    private var isStopRequested = false

    private var executionJob: Job? = null

    fun requestStop() {
        Log.w(TAG, "Emergency stop requested by user!")
        isStopRequested = true
        executionJob?.cancel()
        _executionState.value = ExecutionState.Stopped
    }

    fun executeAutomation(
        context: Context,
        automation: Automation,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        if (_executionState.value is ExecutionState.Running) {
            Log.w(TAG, "Another automation is already executing")
            onComplete?.invoke(false, "Another automation is currently running")
            return
        }

        isStopRequested = false
        val totalSteps = automation.steps.size

        executionJob = CoroutineScope(Dispatchers.IO).launch {
            val db = VoiceAgentDatabase.getInstance(context)
            val startTime = System.currentTimeMillis()
            val stepExecutionDetails = mutableListOf<StepExecutionDetail>()
            var failedStepTitle: String? = null
            var failureReason: String? = null
            var isOverallSuccess = true
            var completedCount = 0

            Log.i(TAG, "Starting workflow '${automation.name}' with $totalSteps steps")

            // Spoken start announcement
            if (automation.speakStepProgress) {
                TextToSpeechManager.speak("Starting automation: ${automation.name}")
            }

            // Start Foreground Service with initial notification
            AutomationForegroundService.start(
                context,
                automation.name,
                "Initializing automation...",
                1,
                totalSteps
            )

            val service = VoiceAccessibilityService.instance
            if (service == null) {
                val errorMsg = "Accessibility Service is not enabled. Please enable it in Settings."
                Log.e(TAG, errorMsg)
                recordLogAndFinish(
                    db, context, automation, startTime, false, 0, totalSteps,
                    "Service Check", errorMsg, stepExecutionDetails, onComplete
                )
                return@launch
            }

            var currentStepIndex = 0
            while (currentStepIndex < automation.steps.size) {
                if (isStopRequested) {
                    Log.w(TAG, "Execution aborted by stop request at step $currentStepIndex")
                    isOverallSuccess = false
                    failureReason = "Stopped by user"
                    break
                }

                val step = automation.steps[currentStepIndex]
                _executionState.value = ExecutionState.Running(
                    automation = automation,
                    currentStepIndex = currentStepIndex,
                    currentStep = step,
                    totalSteps = totalSteps
                )

                // Update Foreground notification
                AutomationForegroundService.start(
                    context,
                    automation.name,
                    "${step.order}. ${step.title}",
                    currentStepIndex + 1,
                    totalSteps
                )

                // Optional spoken step progress
                if (automation.speakStepProgress && !step.spokenMessage.isNullOrBlank()) {
                    TextToSpeechManager.speak(step.spokenMessage)
                }

                // Pre-delay
                if (step.delayBeforeMs > 0) {
                    delay(step.delayBeforeMs)
                }

                // Execute step with retry logic
                var stepSuccess = false
                var stepError: String? = null
                val attempts = (step.maxRetries + 1).coerceAtLeast(1)

                for (attempt in 1..attempts) {
                    if (isStopRequested) break
                    try {
                        stepSuccess = executeSingleStep(context, service, db, step)
                        if (stepSuccess) break
                    } catch (e: Exception) {
                        stepError = e.message
                        Log.e(TAG, "Error executing step '${step.title}' attempt $attempt: $stepError")
                    }
                    if (attempt < attempts && !isStopRequested) {
                        delay(600) // retry backoff
                    }
                }

                val detail = StepExecutionDetail(
                    stepOrder = step.order,
                    stepTitle = step.title,
                    actionType = step.actionType,
                    status = if (stepSuccess) StepStatus.SUCCESS else StepStatus.FAILED,
                    message = if (stepSuccess) "Completed successfully" else (stepError ?: "Failed to locate target element")
                )
                stepExecutionDetails.add(detail)

                if (stepSuccess) {
                    completedCount++
                } else {
                    if (step.stopOnFailure) {
                        isOverallSuccess = false
                        failedStepTitle = step.title
                        failureReason = stepError ?: "Element not found or action timed out"
                        Log.e(TAG, "Step '${step.title}' failed and stopOnFailure is true. Aborting.")
                        break
                    } else {
                        Log.w(TAG, "Step '${step.title}' failed but stopOnFailure is false. Continuing.")
                    }
                }

                // Post-delay
                if (step.delayAfterMs > 0) {
                    delay(step.delayAfterMs)
                }

                currentStepIndex++
            }

            // Stop Foreground Service
            AutomationForegroundService.stop(context)

            // Final status and database log
            recordLogAndFinish(
                db, context, automation, startTime, isOverallSuccess,
                completedCount, totalSteps, failedStepTitle, failureReason,
                stepExecutionDetails, onComplete
            )
        }
    }

    private suspend fun executeSingleStep(
        context: Context,
        service: VoiceAccessibilityService,
        db: VoiceAgentDatabase,
        step: AutomationStep
    ): Boolean {
        when (step.actionType) {
            ActionType.LAUNCH_APP -> {
                val pkg = step.targetPackage ?: return false
                val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                } ?: return false
                context.startActivity(intent)
                return true
            }

            ActionType.OPEN_URL -> {
                val urlStr = step.url ?: return false
                val uri = if (urlStr.startsWith("http://") || urlStr.startsWith("https://")) {
                    Uri.parse(urlStr)
                } else {
                    Uri.parse("http://$urlStr")
                }
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    step.targetPackage?.let { setPackage(it) }
                }
                context.startActivity(intent)
                return true
            }

            ActionType.WAIT_FOR_ELEMENT -> {
                val node = waitForNode(service, step)
                return node != null
            }

            ActionType.CLICK_ELEMENT -> {
                val node = waitForNode(service, step)
                if (node != null) {
                    return service.clickNode(node)
                }
                // Fallback to screen coordinates if provided
                if (step.clickX != null && step.clickY != null) {
                    var clicked = false
                    service.clickCoordinates(step.clickX, step.clickY) { clicked = it }
                    delay(200)
                    return true
                }
                return false
            }

            ActionType.CLICK_COORDINATE -> {
                if (step.clickX != null && step.clickY != null) {
                    service.clickCoordinates(step.clickX, step.clickY)
                    return true
                }
                return false
            }

            ActionType.SET_TEXT -> {
                val node = waitForNode(service, step) ?: return false
                val text = step.textPayload ?: ""
                return service.setTextOnNode(node, text)
            }

            ActionType.INJECT_CREDENTIAL -> {
                val key = step.credentialKey ?: return false
                val cred = db.credentialDao().getCredentialByKey(key) ?: run {
                    Log.e(TAG, "Credential key '$key' not found in secure storage")
                    return false
                }
                val decryptedPassword = KeystoreManager.decrypt(cred.encryptedData, cred.iv)
                val node = waitForNode(service, step) ?: return false
                val success = service.setTextOnNode(node, decryptedPassword)
                db.credentialDao().updateLastUsed(key, System.currentTimeMillis())
                return success
            }

            ActionType.CLEAR_TEXT -> {
                val node = waitForNode(service, step) ?: return false
                return service.clearTextOnNode(node)
            }

            ActionType.DELAY -> {
                val waitMs = step.delayAfterMs.coerceAtLeast(100)
                delay(waitMs)
                return true
            }

            ActionType.SCROLL_FORWARD -> {
                val node = waitForNode(service, step) ?: service.rootInActiveWindow
                return if (node != null) service.scrollNode(node, forward = true) else false
            }

            ActionType.SCROLL_BACKWARD -> {
                val node = waitForNode(service, step) ?: service.rootInActiveWindow
                return if (node != null) service.scrollNode(node, forward = false) else false
            }

            ActionType.SWIPE -> {
                if (step.swipeStartX != null && step.swipeStartY != null && step.swipeEndX != null && step.swipeEndY != null) {
                    service.swipe(step.swipeStartX, step.swipeStartY, step.swipeEndX, step.swipeEndY, step.swipeDurationMs)
                    return true
                }
                return false
            }

            ActionType.PRESS_BACK -> {
                return service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            }

            ActionType.PRESS_HOME -> {
                return service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            }

            ActionType.CHECK_ELEMENT_EXISTS -> {
                val node = service.findNode(
                    resourceId = step.selectorResourceId,
                    text = step.selectorText,
                    contentDesc = step.selectorContentDescription,
                    className = step.selectorClassName
                )
                return node != null
            }

            ActionType.CHECK_TEXT_EXISTS -> {
                val node = service.findNode(text = step.selectorText)
                return node != null
            }

            ActionType.SPEAK_TEXT -> {
                step.textPayload?.let { TextToSpeechManager.speak(it) }
                return true
            }

            else -> {
                Log.w(TAG, "Unhandled action type: ${step.actionType}")
                return true
            }
        }
    }

    private suspend fun waitForNode(
        service: VoiceAccessibilityService,
        step: AutomationStep
    ): AccessibilityNodeInfo? {
        val timeoutMs = (step.timeoutSeconds.coerceAtLeast(2)) * 1000L
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isStopRequested) return null
            val node = service.findNode(
                resourceId = step.selectorResourceId,
                text = step.selectorText,
                contentDesc = step.selectorContentDescription,
                className = step.selectorClassName
            )
            if (node != null) return node
            delay(250) // Polling interval
        }
        return null
    }

    private suspend fun recordLogAndFinish(
        db: VoiceAgentDatabase,
        context: Context,
        automation: Automation,
        startTime: Long,
        isSuccess: Boolean,
        completedCount: Int,
        totalSteps: Int,
        failedStepTitle: String?,
        failureReason: String?,
        stepDetails: List<StepExecutionDetail>,
        onComplete: ((Boolean, String?) -> Unit)?
    ) {
        val finishTime = System.currentTimeMillis()
        val log = ExecutionLog(
            automationId = automation.id,
            automationName = automation.name,
            startedAt = startTime,
            finishedAt = finishTime,
            isSuccess = isSuccess,
            completedStepsCount = completedCount,
            totalStepsCount = totalSteps,
            failedStepTitle = failedStepTitle,
            failureReason = failureReason,
            stepDetails = stepDetails
        )

        db.executionLogDao().insertLog(log)
        db.automationDao().updateExecutionStats(
            id = automation.id,
            time = finishTime,
            successIncr = if (isSuccess) 1 else 0,
            failIncr = if (isSuccess) 0 else 1
        )

        if (isSuccess) {
            _executionState.value = ExecutionState.Completed(log)
            if (automation.speakStepProgress) {
                TextToSpeechManager.speak("Automation ${automation.name} completed successfully")
            }
        } else {
            _executionState.value = ExecutionState.Failed(log, failureReason ?: "Execution failed")
            if (automation.speakStepProgress) {
                TextToSpeechManager.speak("Automation ${automation.name} failed at $failedStepTitle")
            }
        }

        withContext(Dispatchers.Main) {
            onComplete?.invoke(isSuccess, failureReason)
        }
    }
}
