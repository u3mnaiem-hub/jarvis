package com.voiceagent.automation.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.voiceagent.automation.data.db.VoiceAgentDatabase
import com.voiceagent.automation.model.*
import com.voiceagent.automation.security.BankingProtectionManager
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
            var failureCategory: String? = null
            var suggestedAction: String? = null
            var isOverallSuccess = true
            var completedCount = 0

            Log.i(TAG, "Starting workflow '${automation.name}' with $totalSteps steps")

            // Spoken start announcement
            if (automation.speakStepProgress) {
                TextToSpeechManager.speak("Starting: ${automation.name}")
            }

            // Start Foreground Service
            AutomationForegroundService.start(
                context,
                automation.name,
                "Initializing automation...",
                1,
                totalSteps
            )

            // For actions that need accessibility, check service
            val needsAccessibility = automation.steps.any { 
                it.actionType in listOf(
                    ActionType.CLICK_ELEMENT,
                    ActionType.CLICK_COORDINATE,
                    ActionType.SET_TEXT,
                    ActionType.INJECT_CREDENTIAL,
                    ActionType.CLEAR_TEXT,
                    ActionType.WAIT_FOR_ELEMENT,
                    ActionType.SCROLL_FORWARD,
                    ActionType.SCROLL_BACKWARD,
                    ActionType.SWIPE,
                    ActionType.PRESS_BACK,
                    ActionType.PRESS_HOME
                )
            }

            val service = VoiceAccessibilityService.instance
            if (needsAccessibility && service == null) {
                val errorMsg = "Accessibility Service is not enabled. Please enable it in Settings to run UI automation."
                Log.e(TAG, errorMsg)
                recordLogAndFinish(
                    db, context, automation, startTime, false, 0, totalSteps,
                    "Service Check", errorMsg, "Accessibility Disabled",
                    "Enable Voice Agent in Android Settings -> Accessibility -> Installed Apps.",
                    stepExecutionDetails, onComplete
                )
                return@launch
            }

            var currentStepIndex = 0
            while (currentStepIndex < automation.steps.size) {
                if (isStopRequested) {
                    Log.w(TAG, "Execution aborted by stop request at step $currentStepIndex")
                    isOverallSuccess = false
                    failureReason = "Execution stopped by user"
                    failureCategory = "User Cancelled"
                    suggestedAction = "You can restart the workflow when ready."
                    break
                }

                val step = automation.steps[currentStepIndex]
                val stepStartTime = System.currentTimeMillis()

                // Skip disabled steps
                if (!step.isStepEnabled) {
                    Log.d(TAG, "Skipping disabled step: ${step.title}")
                    stepExecutionDetails.add(
                        StepExecutionDetail(
                            stepOrder = step.order,
                            stepTitle = step.title,
                            actionType = step.actionType,
                            status = StepStatus.SKIPPED,
                            startedAt = stepStartTime,
                            finishedAt = System.currentTimeMillis(),
                            message = "Step is disabled"
                        )
                    )
                    currentStepIndex++
                    continue
                }

                // SECURITY CHECK: Banking Protection
                if (!step.targetPackage.isNullOrBlank() && BankingProtectionManager.isPackageProtected(context, step.targetPackage)) {
                    val secMsg = "Security Protection: Automation against financial app '${step.targetPackage}' is strictly blocked."
                    Log.e(TAG, secMsg)
                    isOverallSuccess = false
                    failedStepTitle = step.title
                    failureReason = secMsg
                    failureCategory = "Banking Protection"
                    suggestedAction = "Banking applications cannot be automated for financial security."

                    stepExecutionDetails.add(
                        StepExecutionDetail(
                            stepOrder = step.order,
                            stepTitle = step.title,
                            actionType = step.actionType,
                            status = StepStatus.FAILED,
                            startedAt = stepStartTime,
                            finishedAt = System.currentTimeMillis(),
                            expectedElement = step.targetPackage,
                            elementFound = false,
                            actionAttempted = false,
                            actionConfirmed = false,
                            failureReason = secMsg,
                            suggestedAction = suggestedAction,
                            message = secMsg
                        )
                    )
                    break
                }

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
                var actionAttempted = false
                var actionConfirmed = false
                var elementFound = false
                var actualRetries = 0
                val attempts = (step.maxRetries + 1).coerceAtLeast(1)

                for (attempt in 1..attempts) {
                    if (isStopRequested) break
                    actualRetries = attempt - 1
                    try {
                        val result = executeSingleStep(context, service, db, step)
                        stepSuccess = result.success
                        stepError = result.errorMessage
                        actionAttempted = result.actionAttempted
                        actionConfirmed = result.actionConfirmed
                        elementFound = result.elementFound
                        if (stepSuccess) break
                    } catch (e: Exception) {
                        stepError = e.message
                        Log.e(TAG, "Error executing step '${step.title}' attempt $attempt: $stepError")
                    }
                    if (attempt < attempts && !isStopRequested) {
                        delay(600)
                    }
                }

                // Post-verification check if specified
                if (stepSuccess && !step.verificationText.isNullOrBlank() && service != null) {
                    val verifyNode = waitForVerification(service, step.verificationText, step.verificationResourceId, 5)
                    if (verifyNode != null) {
                        actionConfirmed = true
                    } else {
                        stepSuccess = false
                        stepError = "Expected verification element '${step.verificationText}' did not appear."
                        actionConfirmed = false
                    }
                }

                val stepFinishedTime = System.currentTimeMillis()
                val detail = StepExecutionDetail(
                    stepOrder = step.order,
                    stepTitle = step.title,
                    actionType = step.actionType,
                    status = if (stepSuccess) StepStatus.SUCCESS else StepStatus.FAILED,
                    startedAt = stepStartTime,
                    finishedAt = stepFinishedTime,
                    expectedElement = step.selectorText ?: step.selectorResourceId ?: step.candidateTexts.firstOrNull() ?: step.url,
                    elementFound = elementFound,
                    actionAttempted = actionAttempted,
                    actionConfirmed = actionConfirmed,
                    retryCount = actualRetries,
                    failureReason = if (stepSuccess) null else stepError,
                    suggestedAction = if (stepSuccess) null else classifySuggestedAction(step, stepError),
                    message = if (stepSuccess) "Action executed and verified successfully" else (stepError ?: "Failed to execute step")
                )
                stepExecutionDetails.add(detail)

                if (stepSuccess) {
                    completedCount++
                } else {
                    if (step.stopOnFailure) {
                        isOverallSuccess = false
                        failedStepTitle = step.title
                        failureReason = stepError ?: "Element not found or timed out"
                        failureCategory = classifyFailureCategory(step, stepError)
                        suggestedAction = classifySuggestedAction(step, stepError)
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
                failureCategory, suggestedAction,
                stepExecutionDetails, onComplete
            )
        }
    }

    private data class StepResult(
        val success: Boolean,
        val errorMessage: String? = null,
        val actionAttempted: Boolean = true,
        val actionConfirmed: Boolean = true,
        val elementFound: Boolean = true
    )

    private suspend fun executeSingleStep(
        context: Context,
        service: VoiceAccessibilityService?,
        db: VoiceAgentDatabase,
        step: AutomationStep
    ): StepResult {
        when (step.actionType) {
            ActionType.LAUNCH_APP -> {
                val pkg = step.targetPackage ?: return StepResult(false, "Target package is not specified", actionAttempted = false, elementFound = false)
                val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                } ?: return StepResult(false, "Application '$pkg' is not installed on this device", actionAttempted = false, elementFound = false)
                context.startActivity(intent)
                return StepResult(true, actionAttempted = true, actionConfirmed = true)
            }

            ActionType.OPEN_URL -> {
                val urlStr = step.url ?: return StepResult(false, "URL is not specified", actionAttempted = false, elementFound = false)
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
                return StepResult(true, actionAttempted = true, actionConfirmed = true)
            }

            ActionType.SEND_DEEP_LINK -> {
                val uriStr = step.deepLinkUri ?: step.url ?: return StepResult(false, "Deep link URI is not specified", actionAttempted = false, elementFound = false)
                val uri = Uri.parse(uriStr)
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    step.targetPackage?.let { setPackage(it) }
                }
                try {
                    context.startActivity(intent)
                    return StepResult(true, actionAttempted = true, actionConfirmed = true)
                } catch (e: Exception) {
                    return StepResult(false, "Failed to launch deep link '$uriStr': ${e.message}", actionAttempted = true, actionConfirmed = false)
                }
            }

            ActionType.WAIT_FOR_ELEMENT -> {
                if (service == null) return StepResult(false, "Accessibility Service is not connected", actionAttempted = false, elementFound = false)
                val node = waitForNode(service, step)
                return if (node != null) {
                    StepResult(true, elementFound = true, actionAttempted = true, actionConfirmed = true)
                } else {
                    StepResult(false, "Element '${step.selectorText ?: step.selectorResourceId ?: step.candidateTexts.joinToString()}' not found within timeout", elementFound = false, actionAttempted = true, actionConfirmed = false)
                }
            }

            ActionType.CLICK_ELEMENT -> {
                if (service == null) return StepResult(false, "Accessibility Service is not connected", actionAttempted = false, elementFound = false)
                val node = waitForNode(service, step)
                if (node != null) {
                    val clicked = service.clickNode(node)
                    return StepResult(clicked, if (clicked) null else "Found element but failed to click node", elementFound = true, actionAttempted = true, actionConfirmed = clicked)
                }
                // Fallback to screen coordinates if provided
                if (step.clickX != null && step.clickY != null) {
                    var clicked = false
                    service.clickCoordinates(step.clickX, step.clickY) { clicked = it }
                    delay(250)
                    return StepResult(true, elementFound = false, actionAttempted = true, actionConfirmed = true)
                }
                val label = step.selectorText ?: step.selectorResourceId ?: step.candidateTexts.joinToString("/") ?: "Button"
                return StepResult(false, "Target element '$label' could not be found to click", elementFound = false, actionAttempted = true, actionConfirmed = false)
            }

            ActionType.CLICK_COORDINATE -> {
                if (service == null) return StepResult(false, "Accessibility Service is not connected", actionAttempted = false, elementFound = false)
                if (step.clickX != null && step.clickY != null) {
                    service.clickCoordinates(step.clickX, step.clickY)
                    return StepResult(true, elementFound = true, actionAttempted = true, actionConfirmed = true)
                }
                return StepResult(false, "Coordinates (X, Y) are not specified", actionAttempted = false, elementFound = false)
            }

            ActionType.SET_TEXT -> {
                if (service == null) return StepResult(false, "Accessibility Service is not connected", actionAttempted = false, elementFound = false)
                val node = waitForNode(service, step) ?: return StepResult(false, "Input field '${step.selectorText ?: step.selectorResourceId}' not found", elementFound = false, actionAttempted = true, actionConfirmed = false)
                val text = step.textPayload ?: ""
                val success = service.setTextOnNode(node, text)
                return StepResult(success, if (success) null else "Failed to enter text on node", elementFound = true, actionAttempted = true, actionConfirmed = success)
            }

            ActionType.INJECT_CREDENTIAL -> {
                if (service == null) return StepResult(false, "Accessibility Service is not connected", actionAttempted = false, elementFound = false)
                val key = step.credentialKey ?: return StepResult(false, "Credential key is not specified", actionAttempted = false, elementFound = false)
                val cred = db.credentialDao().getCredentialByKey(key) ?: return StepResult(false, "Credential key '$key' not found in vault", actionAttempted = false, elementFound = false)
                val decryptedPassword = KeystoreManager.decrypt(cred.encryptedData, cred.iv)
                val node = waitForNode(service, step) ?: return StepResult(false, "Password input field not found", elementFound = false, actionAttempted = true, actionConfirmed = false)
                val success = service.setTextOnNode(node, decryptedPassword)
                db.credentialDao().updateLastUsed(key, System.currentTimeMillis())
                return StepResult(success, if (success) null else "Failed to inject password into input field", elementFound = true, actionAttempted = true, actionConfirmed = success)
            }

            ActionType.CLEAR_TEXT -> {
                if (service == null) return StepResult(false, "Accessibility Service is not connected", actionAttempted = false, elementFound = false)
                val node = waitForNode(service, step) ?: return StepResult(false, "Input field to clear not found", elementFound = false, actionAttempted = true, actionConfirmed = false)
                val success = service.clearTextOnNode(node)
                return StepResult(success, if (success) null else "Failed to clear text on node", elementFound = true, actionAttempted = true, actionConfirmed = success)
            }

            ActionType.DELAY -> {
                val waitMs = step.delayAfterMs.coerceAtLeast(100)
                delay(waitMs)
                return StepResult(true, actionAttempted = true, actionConfirmed = true)
            }

            ActionType.SCROLL_FORWARD -> {
                if (service == null) return StepResult(false, "Accessibility Service is not connected", actionAttempted = false, elementFound = false)
                val node = waitForNode(service, step) ?: service.rootInActiveWindow
                val success = if (node != null) service.scrollNode(node, forward = true) else false
                return StepResult(success, if (success) null else "Failed to scroll forward", elementFound = node != null, actionAttempted = true, actionConfirmed = success)
            }

            ActionType.SCROLL_BACKWARD -> {
                if (service == null) return StepResult(false, "Accessibility Service is not connected", actionAttempted = false, elementFound = false)
                val node = waitForNode(service, step) ?: service.rootInActiveWindow
                val success = if (node != null) service.scrollNode(node, forward = false) else false
                return StepResult(success, if (success) null else "Failed to scroll backward", elementFound = node != null, actionAttempted = true, actionConfirmed = success)
            }

            ActionType.SWIPE -> {
                if (service == null) return StepResult(false, "Accessibility Service is not connected", actionAttempted = false, elementFound = false)
                if (step.swipeStartX != null && step.swipeStartY != null && step.swipeEndX != null && step.swipeEndY != null) {
                    service.swipe(step.swipeStartX, step.swipeStartY, step.swipeEndX, step.swipeEndY, step.swipeDurationMs)
                    return StepResult(true, elementFound = true, actionAttempted = true, actionConfirmed = true)
                }
                return StepResult(false, "Swipe coordinates are missing", actionAttempted = false, elementFound = false)
            }

            ActionType.PRESS_BACK -> {
                if (service == null) return StepResult(false, "Accessibility Service is not connected", actionAttempted = false, elementFound = false)
                val success = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                return StepResult(success, actionAttempted = true, actionConfirmed = success)
            }

            ActionType.PRESS_HOME -> {
                if (service == null) return StepResult(false, "Accessibility Service is not connected", actionAttempted = false, elementFound = false)
                val success = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                return StepResult(success, actionAttempted = true, actionConfirmed = success)
            }

            ActionType.CHECK_ELEMENT_EXISTS -> {
                if (service == null) return StepResult(false, "Accessibility Service is not connected", actionAttempted = false, elementFound = false)
                val node = service.findNode(
                    resourceId = step.selectorResourceId,
                    text = step.selectorText,
                    contentDesc = step.selectorContentDescription,
                    className = step.selectorClassName,
                    candidateTexts = step.candidateTexts
                )
                return StepResult(node != null, if (node != null) null else "Condition failed: element does not exist", elementFound = node != null, actionAttempted = true, actionConfirmed = node != null)
            }

            ActionType.CHECK_TEXT_EXISTS -> {
                if (service == null) return StepResult(false, "Accessibility Service is not connected", actionAttempted = false, elementFound = false)
                val node = service.findNode(text = step.selectorText, candidateTexts = step.candidateTexts)
                return StepResult(node != null, if (node != null) null else "Condition failed: text does not exist", elementFound = node != null, actionAttempted = true, actionConfirmed = node != null)
            }

            ActionType.SPEAK_TEXT -> {
                step.textPayload?.let { TextToSpeechManager.speak(it) }
                return StepResult(true, actionAttempted = true, actionConfirmed = true)
            }

            else -> {
                Log.w(TAG, "Unhandled action type: ${step.actionType}")
                return StepResult(true, actionAttempted = true, actionConfirmed = true)
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
                className = step.selectorClassName,
                candidateTexts = step.candidateTexts
            )
            if (node != null) return node
            delay(250)
        }
        return null
    }

    private suspend fun waitForVerification(
        service: VoiceAccessibilityService,
        verifyText: String?,
        verifyResId: String?,
        timeoutSec: Int
    ): AccessibilityNodeInfo? {
        val timeoutMs = timeoutSec * 1000L
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isStopRequested) return null
            val node = service.findNode(resourceId = verifyResId, text = verifyText)
            if (node != null) return node
            delay(300)
        }
        return null
    }

    private fun classifyFailureCategory(step: AutomationStep, error: String?): String {
        val err = error?.lowercase() ?: ""
        return when {
            err.contains("accessibility") -> "Permission Missing"
            err.contains("banking") -> "Banking Security Protection"
            err.contains("not installed") -> "App Not Installed"
            err.contains("verification") -> "Page Verification Failed"
            err.contains("timed out") || err.contains("not found") -> "Element Detection Timeout"
            err.contains("cancelled") || err.contains("stopped") -> "User Aborted"
            else -> "Execution Interrupted"
        }
    }

    private fun classifySuggestedAction(step: AutomationStep, error: String?): String {
        val err = error?.lowercase() ?: ""
        return when {
            err.contains("accessibility") -> "Open Android Settings and turn ON Voice Agent under Accessibility Services."
            err.contains("banking") -> "Financial applications cannot be automated for security and privacy."
            err.contains("not installed") -> "Ensure target application '${step.targetPackage}' is installed from Google Play."
            err.contains("verification") -> "Verify router login credentials or check if the router page changed."
            err.contains("timed out") || err.contains("not found") -> "Increase the step timeout or check if page layout differs on this device."
            else -> "Inspect step parameters or test the step manually in the Workflow Editor."
        }
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
        failureCategory: String?,
        suggestedAction: String?,
        stepDetails: List<StepExecutionDetail>,
        onComplete: ((Boolean, String?) -> Unit)?
    ) {
        val finishedTime = System.currentTimeMillis()
        val log = ExecutionLog(
            automationId = automation.id,
            automationName = automation.name,
            startedAt = startTime,
            finishedAt = finishedTime,
            isSuccess = isSuccess,
            completedStepsCount = completedCount,
            totalStepsCount = totalSteps,
            failedStepTitle = failedStepTitle,
            failureReason = failureReason,
            failureCategory = failureCategory,
            suggestedAction = suggestedAction,
            stepDetails = stepDetails
        )

        try {
            db.executionLogDao().insertLog(log)
            db.automationDao().updateExecutionStats(
                id = automation.id,
                time = finishedTime,
                successIncr = if (isSuccess) 1 else 0,
                failIncr = if (isSuccess) 0 else 1
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist execution log: ${e.message}")
        }

        _executionState.value = if (isSuccess) {
            ExecutionState.Completed(log)
        } else {
            ExecutionState.Failed(log, failureReason ?: "Unknown error")
        }

        if (automation.speakStepProgress) {
            if (isSuccess) {
                TextToSpeechManager.speak("Workflow completed successfully")
            } else {
                TextToSpeechManager.speak("Execution stopped: ${failedStepTitle ?: "Error"}")
            }
        }

        onComplete?.invoke(isSuccess, failureReason)
    }
}
