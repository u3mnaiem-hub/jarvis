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
import com.voiceagent.automation.security.SafeModeManager
import com.voiceagent.automation.service.AutomationForegroundService
import com.voiceagent.automation.service.VoiceAccessibilityService
import com.voiceagent.automation.voice.TextToSpeechManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume

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
            if (automation.speakStepProgress && SafeModeManager.isTtsEnabled(context)) {
                TextToSpeechManager.speak("Starting: ${automation.name}")
            }

            // Start Foreground Service
            AutomationForegroundService.start(
                context,
                automation.name,
                "Initializing automation...",
                1,
                totalSteps.coerceAtLeast(1)
            )

            val isWebMacro = automation.macroType == MacroType.WEB_MACRO
            var webEngine: WebMacroEngine? = if (isWebMacro) WebMacroEngine(context) else null
            var lastWaitedElementText: String? = null

            try {
                // Only ScreenMacro steps require AccessibilityService (WebMacro runs inside app WebView)
                val needsAccessibility = !isWebMacro && automation.steps.any {
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
                        ActionType.PRESS_HOME,
                        ActionType.PRESS_ENTER,
                        ActionType.CHECK_ELEMENT_EXISTS,
                        ActionType.CHECK_TEXT_EXISTS
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
                        totalSteps = totalSteps.coerceAtLeast(1)
                    )

                    // Update Foreground notification
                    AutomationForegroundService.start(
                        context,
                        automation.name,
                        "${step.order}. ${step.title}",
                        currentStepIndex + 1,
                        totalSteps.coerceAtLeast(1)
                    )

                    // Optional spoken step progress
                    if (automation.speakStepProgress && SafeModeManager.isTtsEnabled(context) && !step.spokenMessage.isNullOrBlank()) {
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
                    val effectiveRetries = if (isLoginOrSubmitStep(step) || step.actionType == ActionType.INJECT_CREDENTIAL) {
                        step.maxRetries.coerceAtMost(2) // Never lock router admin page with >2 login retries
                    } else {
                        step.maxRetries.coerceIn(1, 5)
                    }
                    val attempts = (effectiveRetries + 1).coerceAtLeast(1)

                    for (attempt in 1..attempts) {
                        if (isStopRequested) break
                        actualRetries = attempt - 1
                        try {
                            val result = executeSingleStep(context, service, db, step, webEngine, lastWaitedElementText)
                            stepSuccess = result.success
                            stepError = result.errorMessage
                            actionAttempted = result.actionAttempted
                            actionConfirmed = result.actionConfirmed
                            elementFound = result.elementFound
                            if (stepSuccess) {
                                if (step.actionType == ActionType.WAIT_FOR_ELEMENT && !step.selectorText.isNullOrBlank()) {
                                    lastWaitedElementText = step.selectorText
                                }
                                break
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            stepError = e.message
                            Log.e(TAG, "Error executing step '${step.title}' attempt $attempt: $stepError")
                        }
                        if (attempt < attempts && !isStopRequested) {
                            delay(1200)
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

                    // Conditional jump handling
                    val jumpTargetId = if (stepSuccess) step.onConditionMetJumpToStepId else step.onConditionFailedJumpToStepId
                    if (!jumpTargetId.isNullOrBlank()) {
                        val jumpIdx = automation.steps.indexOfFirst { it.id == jumpTargetId }
                        if (jumpIdx >= 0) {
                            if (stepSuccess) completedCount++
                            currentStepIndex = jumpIdx
                            continue
                        }
                    }

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

                // Final status and database log
                recordLogAndFinish(
                    db, context, automation, startTime, isOverallSuccess,
                    completedCount, totalSteps, failedStepTitle, failureReason,
                    failureCategory, suggestedAction,
                    stepExecutionDetails, onComplete
                )
            } catch (e: CancellationException) {
                Log.w(TAG, "Automation '${automation.name}' was cancelled")
                withContext(NonCancellable) {
                    recordLogAndFinish(
                        db, context, automation, startTime, false,
                        completedCount, totalSteps, failedStepTitle ?: "Stopped",
                        "Execution stopped by user", "User Aborted",
                        "You can restart the workflow when ready.",
                        stepExecutionDetails, onComplete
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during automation '${automation.name}': ${e.message}", e)
                withContext(NonCancellable) {
                    recordLogAndFinish(
                        db, context, automation, startTime, false,
                        completedCount, totalSteps, failedStepTitle ?: "Unexpected Error",
                        e.message ?: "Unexpected execution error", "Execution Interrupted",
                        "Check step parameters and try again.",
                        stepExecutionDetails, onComplete
                    )
                }
            } finally {
                withContext(NonCancellable) {
                    webEngine?.destroy()
                }
                // Always stop Foreground Service
                AutomationForegroundService.stop(context)
            }
        }
    }

    private data class StepResult(
        val success: Boolean,
        val errorMessage: String? = null,
        val actionAttempted: Boolean = true,
        val actionConfirmed: Boolean = true,
        val elementFound: Boolean = true
    )

    private suspend fun executeWebStep(
        webEngine: WebMacroEngine,
        db: VoiceAgentDatabase,
        step: AutomationStep,
        context: Context,
        lastWaitedElementText: String? = null
    ): StepResult {
        val cssSelectors = buildList {
            step.cssSelector?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(step.candidateCssSelectors.filter { it.isNotBlank() })
            step.selectorResourceId?.takeIf { it.isNotBlank() }?.let { id ->
                add(if (id.startsWith("#") || id.startsWith(".")) id else "#$id")
            }
        }
        val textCandidates = buildList {
            step.selectorText?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(step.candidateTexts.filter { it.isNotBlank() })
            // Fallback: if no text selector was set, infer from ZTE #mm... IDs or "Click: <Name>" title
            if (isEmpty()) {
                step.selectorResourceId?.removePrefix("#")?.removePrefix("mm")?.takeIf { it.isNotBlank() }?.let { add(it) }
                if (step.title.contains(":")) {
                    step.title.substringAfter(":").trim().takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }
        val timeoutMs = (step.timeoutSeconds.coerceAtLeast(3)) * 1000L

        return when (step.actionType) {
            ActionType.OPEN_URL -> {
                val urlStr = step.url ?: return StepResult(false, "URL is not specified", actionAttempted = false, elementFound = false)
                val finalUrl = if (urlStr.startsWith("http://") || urlStr.startsWith("https://")) urlStr else "http://$urlStr"
                val loaded = webEngine.loadUrl(finalUrl, timeoutMs.coerceAtLeast(15000L))
                if (!loaded) {
                    return StepResult(false, "Timed out loading $finalUrl in WebView", elementFound = false, actionAttempted = true, actionConfirmed = false)
                }
                val conflict = webEngine.checkSessionConflict()
                if (conflict != null) {
                    val msg = "Router session conflict: another admin session is already logged in ($conflict). Please log out from the other browser first."
                    if (SafeModeManager.isTtsEnabled(context)) TextToSpeechManager.speak(msg)
                    return StepResult(false, msg, elementFound = false, actionAttempted = true, actionConfirmed = false)
                }
                StepResult(true, actionAttempted = true, actionConfirmed = true)
            }

            ActionType.WAIT_FOR_ELEMENT -> {
                val found = webEngine.waitForElement(cssSelectors, textCandidates, timeoutMs)
                if (found) {
                    StepResult(true, elementFound = true, actionAttempted = true, actionConfirmed = true)
                } else {
                    val dump = if (webEngine.debugMode) "\n[WebDebug] Elements: ${webEngine.dumpInteractiveElements()}" else ""
                    StepResult(false, "Web element '${textCandidates.firstOrNull() ?: cssSelectors.firstOrNull()}' not found within ${timeoutMs / 1000}s.$dump", elementFound = false, actionAttempted = true, actionConfirmed = false)
                }
            }

            ActionType.SET_TEXT -> {
                val value = step.textPayload ?: ""
                val ok = webEngine.fillField(cssSelectors, textCandidates, value, timeoutMs, isSensitive = false)
                StepResult(ok, if (ok) null else "Failed to fill web field '${cssSelectors.firstOrNull() ?: textCandidates.firstOrNull()}'", elementFound = ok, actionAttempted = true, actionConfirmed = ok)
            }

            ActionType.INJECT_CREDENTIAL -> {
                val key = step.credentialKey ?: return StepResult(false, "Credential key is not specified", actionAttempted = false, elementFound = false)
                val cred = db.credentialDao().getCredentialByKey(key)
                    ?: (if (key == "router_password") db.credentialDao().getCredentialByKey("router_admin_pass") else null)
                    ?: return StepResult(false, "Credential key '$key' not found in vault", actionAttempted = false, elementFound = false)
                val decryptedPassword = KeystoreManager.decrypt(cred.encryptedData, cred.iv)
                val ok = webEngine.fillField(cssSelectors, textCandidates, decryptedPassword, timeoutMs, isSensitive = true)
                if (ok) {
                    db.credentialDao().updateLastUsed(key, System.currentTimeMillis())
                }
                StepResult(ok, if (ok) null else "Failed to fill password field in WebView", elementFound = ok, actionAttempted = true, actionConfirmed = ok)
            }

            ActionType.CLICK_ELEMENT -> {
                val isLogin = isLoginOrSubmitStep(step)
                val rowTarget = step.rowContextText?.takeIf { it.isNotBlank() }
                    ?: lastWaitedElementText?.takeIf {
                        textCandidates.any { t ->
                            t.lowercase() in listOf("on", "off", "allow", "block", "enable", "disable", "تفعيل", "تعطيل", "سماح", "حظر")
                        }
                    }
                val ok = if (rowTarget != null && textCandidates.isNotEmpty()) {
                    webEngine.clickInRow(rowTarget, textCandidates, timeoutMs)
                } else {
                    webEngine.clickElement(cssSelectors, textCandidates, timeoutMs, tryLoginFallback = isLogin)
                }
                if (ok && isLogin) {
                    delay(800)
                    val conflict = webEngine.checkSessionConflict()
                    if (conflict != null) {
                        val msg = "Router session conflict detected ($conflict). Only one admin session is allowed at a time."
                        if (SafeModeManager.isTtsEnabled(context)) TextToSpeechManager.speak(msg)
                        return StepResult(false, msg, elementFound = true, actionAttempted = true, actionConfirmed = false)
                    }
                }
                if (!ok) {
                    val dump = if (webEngine.debugMode) "\n[WebDebug] Elements: ${webEngine.dumpInteractiveElements()}" else ""
                    return StepResult(false, "Could not click web element '${textCandidates.firstOrNull() ?: cssSelectors.firstOrNull()}'.$dump", elementFound = false, actionAttempted = true, actionConfirmed = false)
                }
                StepResult(true, elementFound = true, actionAttempted = true, actionConfirmed = true)
            }

            ActionType.PRESS_ENTER -> {
                val ok = webEngine.submitLoginFormFallback()
                StepResult(ok, if (ok) null else "Failed to submit web form via Enter", elementFound = ok, actionAttempted = true, actionConfirmed = ok)
            }

            else -> StepResult(false, "Unsupported web step: ${step.actionType}", actionAttempted = false, elementFound = false)
        }
    }

    private suspend fun executeSingleStep(
        context: Context,
        service: VoiceAccessibilityService?,
        db: VoiceAgentDatabase,
        step: AutomationStep,
        webEngine: WebMacroEngine? = null,
        lastWaitedElementText: String? = null
    ): StepResult {
        if (webEngine != null && step.actionType in listOf(
                ActionType.OPEN_URL,
                ActionType.WAIT_FOR_ELEMENT,
                ActionType.CLICK_ELEMENT,
                ActionType.SET_TEXT,
                ActionType.INJECT_CREDENTIAL,
                ActionType.PRESS_ENTER
            )
        ) {
            return executeWebStep(webEngine, db, step, context, lastWaitedElementText)
        }

        when (step.actionType) {
            ActionType.LAUNCH_APP -> {
                val pkg = step.targetPackage ?: return StepResult(false, "Target package is not specified", actionAttempted = false, elementFound = false)
                val launched = launchTargetPackage(context, pkg)
                if (!launched) {
                    return StepResult(false, "Application '$pkg' is not installed on this device", actionAttempted = false, elementFound = false)
                }
                waitForAppWindow(service, context.packageName, pkg)
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
                    if (!step.targetPackage.isNullOrBlank()) {
                        val isInstalled = try {
                            context.packageManager.getPackageInfo(step.targetPackage, 0)
                            true
                        } catch (e: Exception) {
                            false
                        }
                        if (isInstalled) {
                            setPackage(step.targetPackage)
                        }
                    }
                }
                try {
                    context.startActivity(intent)
                    waitForAppWindow(service, context.packageName, expectedPackage = null)
                    return StepResult(true, actionAttempted = true, actionConfirmed = true)
                } catch (e: Exception) {
                    return StepResult(false, "Failed to open URL '$urlStr': ${e.message}", actionAttempted = true, elementFound = false)
                }
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
                ensureTargetAppInForeground(context, service, step)
                val node = waitForNode(service, step)
                return if (node != null) {
                    StepResult(true, elementFound = true, actionAttempted = true, actionConfirmed = true)
                } else {
                    val snapshot = service.dumpVisibleScreenNodes()
                    val snapInfo = if (snapshot.isNotEmpty()) "\nVisible elements on screen: " + snapshot.joinToString(" | ") else ""
                    StepResult(false, "Element '${step.selectorText ?: step.selectorResourceId ?: step.candidateTexts.joinToString()}' not found within timeout.$snapInfo", elementFound = false, actionAttempted = true, actionConfirmed = false)
                }
            }

            ActionType.CLICK_ELEMENT -> {
                if (service == null) return StepResult(false, "Accessibility Service is not connected", actionAttempted = false, elementFound = false)
                ensureTargetAppInForeground(context, service, step)
                val node = waitForNode(service, step)
                if (node != null) {
                    // Dismiss soft keyboard if it is covering the target element or zoomed viewport
                    if (service.isSoftKeyboardVisible() && !service.isNodeVisibleOnScreen(node)) {
                        service.dismissSoftKeyboard()
                        delay(350)
                    }

                    // Ensure button/circle is scrolled into view (especially if soft keyboard was open)
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            node.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                            delay(150)
                        }
                    } catch (_: Exception) {}

                    // If still off-screen (e.g. browser zoomed in on password field), scroll viewport
                    if (!service.isNodeVisibleOnScreen(node)) {
                        if (!service.scrollFirstScrollable(forward = true)) {
                            val (w, h) = service.getScreenSize()
                            awaitSwipe(service, w * 0.5f, h * 0.65f, w * 0.5f, h * 0.35f, 250L)
                        }
                        delay(250)
                    }

                    val center = service.getNodeCenter(node)
                    val centerOnScreen = center != null && service.isPointOnScreen(center.first, center.second)
                    val pkg = node.packageName?.toString()?.lowercase() ?: ""
                    val isBrowserOrWeb = service.isInsideWebView(node) ||
                        pkg.contains("chrome") || pkg.contains("firefox") || pkg.contains("mozilla") ||
                        pkg.contains("edge") || pkg.contains("sbrowser") || pkg.contains("opera") ||
                        node.isCheckable

                    var clicked = if (isBrowserOrWeb && centerOnScreen) {
                        // Real touch gesture is far more reliable than ACTION_CLICK on visible WebView/browser buttons
                        awaitClickCoordinates(service, center!!.first, center.second) || service.clickNode(node)
                    } else {
                        service.clickNode(node) || (centerOnScreen && awaitClickCoordinates(service, center!!.first, center.second))
                    }

                    // If this is a login/submit button and clicking failed, try submitting via IME Enter
                    if (!clicked && isLoginOrSubmitStep(step)) {
                        clicked = service.pressEnterOnFocusedNode()
                    }
                    return StepResult(clicked, if (clicked) null else "Found element but failed to click node", elementFound = true, actionAttempted = true, actionConfirmed = clicked)
                }
                // For login/submit steps where the button is off-screen/zoomed out, try IME Enter first
                if (isLoginOrSubmitStep(step) && service.pressEnterOnFocusedNode()) {
                    return StepResult(true, elementFound = true, actionAttempted = true, actionConfirmed = true)
                }
                // Fallback to screen coordinates if provided (e.g. unlabeled circle/icon)
                if (step.clickX != null && step.clickY != null) {
                    if (service.isSoftKeyboardVisible()) {
                        service.dismissSoftKeyboard()
                        delay(350)
                    }
                    val clicked = awaitClickCoordinates(service, step.clickX, step.clickY)
                    return StepResult(clicked, if (clicked) null else "Coordinate tap gesture was cancelled", elementFound = false, actionAttempted = true, actionConfirmed = clicked)
                }
                val label = step.selectorText ?: step.selectorResourceId ?: step.candidateTexts.joinToString("/") ?: "Button"
                val snapshot = service.dumpVisibleScreenNodes()
                val snapInfo = if (snapshot.isNotEmpty()) "\n[Diagnostic] Visible elements on screen were: " + snapshot.joinToString(" | ") else ""
                return StepResult(false, "Target element '$label' could not be found to click.$snapInfo", elementFound = false, actionAttempted = true, actionConfirmed = false)
            }

            ActionType.CLICK_COORDINATE -> {
                if (service == null) return StepResult(false, "Accessibility Service is not connected", actionAttempted = false, elementFound = false)
                if (step.clickX != null && step.clickY != null) {
                    val clicked = awaitClickCoordinates(service, step.clickX, step.clickY)
                    return StepResult(clicked, if (clicked) null else "Coordinate tap gesture failed or was cancelled", elementFound = true, actionAttempted = true, actionConfirmed = clicked)
                }
                return StepResult(false, "Coordinates (X, Y) are not specified", actionAttempted = false, elementFound = false)
            }

            ActionType.SET_TEXT -> {
                if (service == null) return StepResult(false, "Accessibility Service is not connected", actionAttempted = false, elementFound = false)
                ensureTargetAppInForeground(context, service, step)
                val text = step.textPayload ?: ""
                return performTextInput(
                    service = service,
                    step = step,
                    text = text,
                    notFoundMsg = "Input field '${step.selectorText ?: step.selectorResourceId}' not found.",
                    failedMsg = "Failed to enter text on node"
                )
            }

            ActionType.INJECT_CREDENTIAL -> {
                if (service == null) return StepResult(false, "Accessibility Service is not connected", actionAttempted = false, elementFound = false)
                val key = step.credentialKey ?: return StepResult(false, "Credential key is not specified", actionAttempted = false, elementFound = false)
                val cred = db.credentialDao().getCredentialByKey(key) ?: return StepResult(false, "Credential key '$key' not found in vault", actionAttempted = false, elementFound = false)
                val decryptedPassword = KeystoreManager.decrypt(cred.encryptedData, cred.iv)
                ensureTargetAppInForeground(context, service, step)
                val result = performTextInput(
                    service = service,
                    step = step,
                    text = decryptedPassword,
                    notFoundMsg = "Password input field not found.",
                    failedMsg = "Failed to inject password into input field"
                )
                if (result.success) {
                    db.credentialDao().updateLastUsed(key, System.currentTimeMillis())
                }
                return result
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
                    val swiped = awaitSwipe(service, step.swipeStartX, step.swipeStartY, step.swipeEndX, step.swipeEndY, step.swipeDurationMs)
                    return StepResult(swiped, if (swiped) null else "Swipe gesture failed or was cancelled", elementFound = true, actionAttempted = true, actionConfirmed = swiped)
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

            ActionType.PRESS_ENTER -> {
                if (service == null) return StepResult(false, "Accessibility Service is not connected", actionAttempted = false, elementFound = false)
                if (service.pressEnterOnFocusedNode()) {
                    return StepResult(true, elementFound = true, actionAttempted = true, actionConfirmed = true)
                }
                // Fallback: dismiss keyboard and click a visible Login/Submit button
                if (service.isSoftKeyboardVisible()) {
                    service.dismissSoftKeyboard()
                    delay(350)
                }
                val submitNode = service.findNode(
                    candidateTexts = listOf("Login", "Log in", "Sign in", "Submit", "Go", "تسجيل الدخول", "دخول")
                )
                if (submitNode != null) {
                    val center = service.getNodeCenter(submitNode)
                    val clicked = if (center != null && service.isPointOnScreen(center.first, center.second)) {
                        awaitClickCoordinates(service, center.first, center.second) || service.clickNode(submitNode)
                    } else {
                        service.clickNode(submitNode)
                    }
                    return StepResult(clicked, if (clicked) null else "Found submit button but failed to click", elementFound = true, actionAttempted = true, actionConfirmed = clicked)
                }
                return StepResult(false, "Could not press Enter or find a Login/Submit button", elementFound = false, actionAttempted = true, actionConfirmed = false)
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

    private suspend fun awaitClickCoordinates(service: VoiceAccessibilityService, x: Float, y: Float): Boolean {
        return withTimeoutOrNull(3000L) {
            suspendCancellableCoroutine { cont ->
                val dispatched = service.clickCoordinates(x, y) { completed ->
                    if (cont.isActive) cont.resume(completed)
                }
                if (!dispatched && cont.isActive) {
                    cont.resume(false)
                }
            }
        } ?: false
    }

    private suspend fun awaitSwipe(
        service: VoiceAccessibilityService,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean {
        return withTimeoutOrNull(durationMs.coerceAtLeast(100) + 3000L) {
            suspendCancellableCoroutine { cont ->
                val dispatched = service.swipe(startX, startY, endX, endY, durationMs) { completed ->
                    if (cont.isActive) cont.resume(completed)
                }
                if (!dispatched && cont.isActive) {
                    cont.resume(false)
                }
            }
        } ?: false
    }

    private fun launchTargetPackage(context: Context, pkg: String): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            return true
        }
        // Fallback for browsers: If requested browser isn't installed, launch the default web browser
        if (pkg.contains("firefox", ignoreCase = true) || pkg.contains("chrome", ignoreCase = true) || pkg.contains("browser", ignoreCase = true)) {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("http://")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (browserIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(browserIntent)
                return true
            }
        }
        return false
    }

    /**
     * If the workflow is still sitting inside VoiceAgent's own UI when a step targets an external app,
     * automatically launch that target app and wait for its window to come to the foreground.
     */
    private suspend fun ensureTargetAppInForeground(
        context: Context,
        service: VoiceAccessibilityService,
        step: AutomationStep
    ) {
        val targetPkg = step.targetPackage?.takeIf { it.isNotBlank() } ?: return
        if (targetPkg == context.packageName) return

        val activePkg = service.rootInActiveWindow?.packageName?.toString()
        if (activePkg == context.packageName) {
            Log.i(TAG, "Still in VoiceAgent UI before step '${step.title}'; auto-launching '$targetPkg'")
            if (launchTargetPackage(context, targetPkg)) {
                waitForAppWindow(service, context.packageName, targetPkg)
            }
        }
    }

    private suspend fun waitForAppWindow(
        service: VoiceAccessibilityService?,
        ownPackage: String,
        expectedPackage: String?,
        timeoutMs: Long = 5000L
    ): Boolean {
        if (service == null) {
            delay(1200L)
            return false
        }
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isStopRequested) return false
            val activePkg = service.rootInActiveWindow?.packageName?.toString()
            val matched = if (expectedPackage != null) {
                activePkg == expectedPackage
            } else {
                activePkg != null && activePkg != ownPackage
            }
            if (matched) return true
            delay(200)
        }
        return false
    }

    private suspend fun performTextInput(
        service: VoiceAccessibilityService,
        step: AutomationStep,
        text: String,
        notFoundMsg: String,
        failedMsg: String
    ): StepResult {
        val node = waitForNode(service, step)
        if (node != null) {
            if (service.setTextOnNode(node, text)) {
                return StepResult(true, elementFound = true, actionAttempted = true, actionConfirmed = true)
            }
            // Tap node center (or recorded coordinates) to focus browser/WebView inputs, then retry
            val tapTarget = service.getNodeCenter(node)
                ?: if (step.clickX != null && step.clickY != null) step.clickX to step.clickY else null
            if (tapTarget != null) {
                awaitClickCoordinates(service, tapTarget.first, tapTarget.second)
                delay(300)
                val targetNode = service.findFocusedInputNode() ?: node
                val retried = service.setTextOnNode(targetNode, text)
                return StepResult(retried, if (retried) null else failedMsg, elementFound = true, actionAttempted = true, actionConfirmed = retried)
            }
            return StepResult(false, failedMsg, elementFound = true, actionAttempted = true, actionConfirmed = false)
        }

        // Coordinate fallback when web input node has no stable resourceId/text
        if (step.clickX != null && step.clickY != null) {
            val tapped = awaitClickCoordinates(service, step.clickX, step.clickY)
            if (tapped) {
                delay(350)
                val focusedNode = service.findFocusedInputNode() ?: service.findNode(
                    resourceId = step.selectorResourceId,
                    text = step.selectorText,
                    contentDesc = step.selectorContentDescription,
                    className = step.selectorClassName,
                    candidateTexts = step.candidateTexts
                )
                if (focusedNode != null) {
                    val success = service.setTextOnNode(focusedNode, text)
                    return StepResult(success, if (success) null else failedMsg, elementFound = true, actionAttempted = true, actionConfirmed = success)
                }
            }
        }

        val snapshot = service.dumpVisibleScreenNodes()
        val snapInfo = if (snapshot.isNotEmpty()) "\n[Diagnostic] Visible elements on screen were: " + snapshot.joinToString(" | ") else ""
        return StepResult(false, "$notFoundMsg$snapInfo", elementFound = false, actionAttempted = true, actionConfirmed = false)
    }

    private fun isLoginOrSubmitStep(step: AutomationStep): Boolean {
        val keywords = listOf("login", "log in", "sign in", "submit", "دخول", "تسجيل الدخول")
        val combined = buildString {
            append(step.title).append(' ')
            append(step.selectorText ?: "").append(' ')
            append(step.selectorResourceId ?: "").append(' ')
            append(step.candidateTexts.joinToString(" "))
        }.lowercase()
        return keywords.any { combined.contains(it) }
    }

    private suspend fun waitForNode(
        service: VoiceAccessibilityService,
        step: AutomationStep
    ): AccessibilityNodeInfo? {
        val timeoutMs = (step.timeoutSeconds.coerceAtLeast(2)) * 1000L
        val startTime = System.currentTimeMillis()
        var keyboardDismissed = false
        var scrollAttempts = 0

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

            val elapsed = System.currentTimeMillis() - startTime
            // After 1.2s without finding the element, dismiss soft keyboard if open (it often hides/zooms the Login button)
            if (!keyboardDismissed && elapsed >= 1200L && service.isSoftKeyboardVisible()) {
                keyboardDismissed = true
                if (service.dismissSoftKeyboard()) {
                    delay(350)
                    continue
                }
            }

            // If still not found after 2s, scroll down up to 2 times to bring off-screen elements into view
            if (scrollAttempts < 2 && elapsed >= 2000L + (scrollAttempts * 900L)) {
                scrollAttempts++
                if (!service.scrollFirstScrollable(forward = true)) {
                    val (w, h) = service.getScreenSize()
                    awaitSwipe(service, w * 0.5f, h * 0.65f, w * 0.5f, h * 0.35f, 250L)
                }
                delay(300)
                continue
            }

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

        if (automation.speakStepProgress && SafeModeManager.isTtsEnabled(context)) {
            if (isSuccess) {
                TextToSpeechManager.speak("Workflow completed successfully")
            } else {
                TextToSpeechManager.speak("Execution stopped: ${failedStepTitle ?: "Error"}")
            }
        }

        onComplete?.invoke(isSuccess, failureReason)
    }
}
